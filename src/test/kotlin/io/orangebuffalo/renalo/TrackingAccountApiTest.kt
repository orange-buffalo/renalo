package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.FundsTransfer
import io.orangebuffalo.renalo.tracking.FundsTransferRepository
import io.orangebuffalo.renalo.tracking.IncomeCategory
import io.orangebuffalo.renalo.tracking.IncomeCategoryRepository
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.tracking.Transaction
import io.orangebuffalo.renalo.tracking.TransactionRepository
import io.orangebuffalo.renalo.tracking.TransactionType
import io.orangebuffalo.renalo.tracking.AccountAdjustment
import io.orangebuffalo.renalo.tracking.AccountAdjustmentRepository
import io.orangebuffalo.renalo.tracking.RecurringTransactionRule
import io.orangebuffalo.renalo.tracking.RecurringTransactionRuleRepository
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.LocalDate

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class TrackingAccountApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var incomeCategoryRepository: IncomeCategoryRepository

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var fundsTransferRepository: FundsTransferRepository

    @Inject
    lateinit var accountAdjustmentRepository: AccountAdjustmentRepository

    @Inject
    lateinit var recurringTransactionRuleRepository: RecurringTransactionRuleRepository

    @Test
    fun requiresRegularUserForTrackingAccounts() {
        val alice = saveUser("alice", UserType.USER)
        val admin = saveUser("admin", UserType.ADMIN)
        saveAccount(alice, "Main", "AUD", 0, isDefault = true)

        val userToken = api().login("alice", "password")
        val adminToken = api().login("admin", "password")
        val body = """
            {"name":"Savings","currency":"EUR","initialBalanceMinor":1000,"isDefault":false}
        """.trimIndent()

        api().get("/api/tracking/accounts", null).statusCode().shouldBe(401)
        api().get("/api/tracking/accounts", adminToken).statusCode().shouldBe(403)
        api().postJson("/api/tracking/accounts", body, null).statusCode().shouldBe(401)
        api().postJson("/api/tracking/accounts", body, adminToken).statusCode().shouldBe(403)
        api().get("/api/tracking/accounts", userToken).statusCode().shouldBe(200)
    }

    @Test
    fun listsOnlyCurrentUsersAccounts() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val aliceMain = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        val aliceSavings = saveAccount(alice, "Savings", "EUR", 12345, isDefault = false)
        val bobAccount = saveAccount(bob, "Bob account", "USD", 999, isDefault = true)
        saveTransaction(alice, aliceMain, saveExpenseCategory(alice), TransactionType.EXPENSE, 1_000)
        saveTransaction(alice, aliceMain, saveIncomeCategory(alice), TransactionType.INCOME, 2_000)
        saveTransfer(alice, aliceMain, aliceSavings, 300, 200)
        accountAdjustmentRepository.save(
            AccountAdjustment(
                userId = alice.id!!,
                trackingAccountId = aliceMain.id!!,
                adjustmentAmountMinor = 100,
                date = LocalDate.parse("2026-06-01"),
            ),
        )
        saveTransaction(bob, bobAccount, saveExpenseCategory(bob), TransactionType.EXPENSE, 9_999)

        val response = api().get("/api/tracking/accounts", api().login("alice", "password"))

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${aliceMain.id},
                    "name": "Main",
                    "currency": "AUD",
                    "initialBalanceMinor": 0,
                    "isDefault": true,
                    "archived": false,
                    "entriesCount": 4
                  },
                  {
                    "id": ${aliceSavings.id},
                    "name": "Savings",
                    "currency": "EUR",
                    "initialBalanceMinor": 12345,
                    "isDefault": false,
                    "archived": false,
                    "entriesCount": 1
                  }
                ]
            """.trimIndent(),
        )
    }

    @Test
    fun hidesArchivedAccountsUnlessRequested() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        val archived = saveAccount(alice, "Old", "AUD", 123, isDefault = false, archived = true)
        val token = api().login("alice", "password")

        val activeResponse = api().get("/api/tracking/accounts", token)
        val allResponse = api().get("/api/tracking/accounts?includeArchived=true", token)

        activeResponse.statusCode().shouldBe(200)
        activeResponse.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${main.id},
                    "name": "Main",
                    "currency": "AUD",
                    "initialBalanceMinor": 0,
                    "isDefault": true,
                    "archived": false,
                    "entriesCount": 0
                  }
                ]
            """.trimIndent(),
        )
        allResponse.statusCode().shouldBe(200)
        allResponse.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${main.id},
                    "name": "Main",
                    "currency": "AUD",
                    "initialBalanceMinor": 0,
                    "isDefault": true,
                    "archived": false,
                    "entriesCount": 0
                  },
                  {
                    "id": ${archived.id},
                    "name": "Old",
                    "currency": "AUD",
                    "initialBalanceMinor": 123,
                    "isDefault": false,
                    "archived": true,
                    "entriesCount": 0
                  }
                ]
            """.trimIndent(),
        )
    }

    @Test
    fun archivesAndUnarchivesTrackingAccounts() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        val bob = saveUser("bob", UserType.USER)
        val bobAccount = saveAccount(bob, "Bob", "AUD", 0, isDefault = true)
        val token = api().login("alice", "password")

        api().post("/api/tracking/accounts/${main.id}/archive", null).statusCode().shouldBe(401)
        api().post("/api/tracking/accounts/${bobAccount.id}/archive", token).statusCode().shouldBe(404)

        val archivedResponse = api().post("/api/tracking/accounts/${main.id}/archive", token)

        archivedResponse.statusCode().shouldBe(200)
        archivedResponse.body().shouldEqualJson(
            """
                {
                  "id": ${main.id},
                  "name": "Main",
                  "currency": "AUD",
                  "initialBalanceMinor": 0,
                  "isDefault": true,
                  "archived": true
                }
            """.trimIndent(),
        )
        trackingAccountRepository.findById(main.id!!).get().archived.shouldBe(true)

        val unarchivedResponse = api().post("/api/tracking/accounts/${main.id}/unarchive", token)

        unarchivedResponse.statusCode().shouldBe(200)
        unarchivedResponse.body().shouldEqualJson(
            """
                {
                  "id": ${main.id},
                  "name": "Main",
                  "currency": "AUD",
                  "initialBalanceMinor": 0,
                  "isDefault": true,
                  "archived": false
                }
            """.trimIndent(),
        )
        trackingAccountRepository.findById(main.id!!).get().archived.shouldBe(false)
    }

    @Test
    fun createsAccountForCurrentUser() {
        val alice = saveUser("alice", UserType.USER)
        saveAccount(alice, "Main", "AUD", 0, isDefault = true)

        val response = api().postJson(
            "/api/tracking/accounts",
            """
                {"name":" Savings ","currency":"eur","initialBalanceMinor":12345,"isDefault":false}
            """.trimIndent(),
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(201)
        val savings = trackingAccountRepository.findByUserIdOrderByName(alice.id!!).first { it.name == "Savings" }
        response.body().shouldEqualJson(
            """
                {
                  "id": ${savings.id},
                  "name": "Savings",
                  "currency": "EUR",
                  "initialBalanceMinor": 12345,
                  "isDefault": false,
                  "archived": false
                }
            """.trimIndent(),
        )
    }

    @Test
    fun updatesAccountAndNominatesNewDefault() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        val savings = saveAccount(alice, "Savings", "EUR", 12345, isDefault = false)

        val response = api().patchJson(
            "/api/tracking/accounts/${savings.id}",
            """
                {"name":"Everyday","currency":"USD","initialBalanceMinor":550,"isDefault":true}
            """.trimIndent(),
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "id": ${savings.id},
                  "name": "Everyday",
                  "currency": "USD",
                  "initialBalanceMinor": 550,
                  "isDefault": true,
                  "archived": false
                }
            """.trimIndent(),
        )
        trackingAccountRepository.findById(main.id!!).get().isDefault.shouldBe(false)
        trackingAccountRepository.findById(savings.id!!).get().isDefault.shouldBe(true)
    }

    @Test
    fun doesNotUncheckCurrentDefaultAccount() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)

        val response = api().patchJson(
            "/api/tracking/accounts/${main.id}",
            """
                {"name":"Main","currency":"AUD","initialBalanceMinor":0,"isDefault":false}
            """.trimIndent(),
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "id": ${main.id},
                  "name": "Main",
                  "currency": "AUD",
                  "initialBalanceMinor": 0,
                  "isDefault": true,
                  "archived": false
                }
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsInvalidAccountsAndOtherUsersAccounts() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val bobAccount = saveAccount(bob, "Bob account", "USD", 0, isDefault = true)
        val token = api().login("alice", "password")

        api().postJson(
            "/api/tracking/accounts",
            """
                {"name":"","currency":"EUR","initialBalanceMinor":0,"isDefault":false}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/accounts",
            """
                {"name":"Savings","currency":"NOPE","initialBalanceMinor":0,"isDefault":false}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(400)
        api().get("/api/tracking/accounts/${bobAccount.id}", token).statusCode().shouldBe(404)
        api().patchJson(
            "/api/tracking/accounts/${bobAccount.id}",
            """
                {"name":"Hacked","currency":"EUR","initialBalanceMinor":0,"isDefault":true}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(404)
    }

    @Test
    fun providesTrackingAccountMergeSummary() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 100, isDefault = true)
        val savings = saveAccount(alice, "Savings", "AUD", 500, isDefault = false)
        saveAccount(alice, "Euro", "EUR", 0, isDefault = false)
        val external = saveAccount(alice, "External", "AUD", 0, isDefault = false)
        saveAccount(bob, "Bob account", "AUD", 0, isDefault = true)
        val expenseCategory = saveExpenseCategory(alice)
        val incomeCategory = saveIncomeCategory(alice)
        saveTransaction(alice, main, expenseCategory, TransactionType.EXPENSE, 1_000)
        saveTransaction(alice, main, incomeCategory, TransactionType.INCOME, 2_000)
        saveTransaction(alice, savings, expenseCategory, TransactionType.EXPENSE, 999)
        saveTransfer(alice, main, external, 300, 300)
        saveTransfer(alice, savings, main, 400, 400)

        val response = api().get("/api/tracking/accounts/${main.id}/merge-summary", api().login("alice", "password"))

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "sourceAccount": {
                    "id": ${main.id},
                    "name": "Main",
                    "currency": "AUD",
                    "initialBalanceMinor": 100,
                    "isDefault": true,
                    "archived": false
                  },
                  "expensesCount": 1,
                  "incomesCount": 1,
                  "transfersCount": 2,
                  "targetAccounts": [
                    {
                      "id": ${external.id},
                      "name": "External",
                      "currency": "AUD",
                      "initialBalanceMinor": 0,
                      "isDefault": false,
                      "archived": false
                    },
                    {
                      "id": ${savings.id},
                      "name": "Savings",
                      "currency": "AUD",
                      "initialBalanceMinor": 500,
                      "isDefault": false,
                      "archived": false
                    }
                  ]
                }
            """.trimIndent(),
        )
    }

    @Test
    fun mergesTrackingAccountIntoSameCurrencyTarget() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 100, isDefault = true)
        val savings = saveAccount(alice, "Savings", "AUD", 500, isDefault = false)
        val external = saveAccount(alice, "External", "AUD", 0, isDefault = false)
        val expenseCategory = saveExpenseCategory(alice)
        val incomeCategory = saveIncomeCategory(alice)
        val expense = saveTransaction(alice, main, expenseCategory, TransactionType.EXPENSE, 1_000)
        val income = saveTransaction(alice, main, incomeCategory, TransactionType.INCOME, 2_000)
        val outgoing = saveTransfer(alice, main, external, 300, 300)
        val incoming = saveTransfer(alice, external, main, 400, 400)
        saveTransfer(alice, main, savings, 500, 500)
        saveTransfer(alice, savings, main, 600, 600)

        val response = api().postJson(
            "/api/tracking/accounts/${main.id}/merge",
            """{"targetAccountId":${savings.id}}""",
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(204)
        trackingAccountRepository.findByIdAndUserId(main.id!!, alice.id!!).shouldBe(null)
        val mergedTarget = trackingAccountRepository.findById(savings.id!!).get()
        mergedTarget.initialBalanceMinor.shouldBe(600)
        mergedTarget.isDefault.shouldBe(true)
        transactionRepository.findById(expense.id!!).get().trackingAccountId.shouldBe(savings.id)
        transactionRepository.findById(income.id!!).get().trackingAccountId.shouldBe(savings.id)
        fundsTransferRepository.findById(outgoing.id!!).get().sourceAccountId.shouldBe(savings.id)
        fundsTransferRepository.findById(incoming.id!!).get().targetAccountId.shouldBe(savings.id)
        fundsTransferRepository.findByUserIdOrderByDateDesc(alice.id!!).size.shouldBe(2)
    }

    @Test
    fun mergePreservesAdjustmentsRecurringRulesAndInternalTransferValue() {
        val alice = saveUser("alice", UserType.USER)
        val source = saveAccount(alice, "Source", "AUD", 1_000, isDefault = true)
        val target = saveAccount(alice, "Target", "AUD", -200, isDefault = false)
        val expenseCategory = saveExpenseCategory(alice)
        val adjustment = accountAdjustmentRepository.save(
            AccountAdjustment(
                userId = alice.id!!,
                trackingAccountId = source.id!!,
                adjustmentAmountMinor = 75,
                date = LocalDate.parse("2026-06-01"),
            ),
        )
        val rule = recurringTransactionRuleRepository.save(
            RecurringTransactionRule(
                userId = alice.id!!,
                transactionType = TransactionType.EXPENSE,
                trackingAccountId = source.id!!,
                categoryId = expenseCategory.id!!,
                startDate = LocalDate.parse("2026-06-01"),
                recurrenceFrequency = 1,
                recurrenceInterval = RecurrenceInterval.MONTH,
                generatedUntil = LocalDate.parse("2026-06-01"),
                amountMinor = 100,
            ),
        )
        saveTransfer(alice, source, target, 500, 450)
        saveTransfer(alice, target, source, 200, 230)

        val response = api().postJson(
            "/api/tracking/accounts/${source.id}/merge",
            """{"targetAccountId":${target.id}}""",
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(204)
        val mergedTarget = trackingAccountRepository.findById(target.id!!).get()
        // Initial balances 1,000 - 200 plus internal transfer net (-50 + 30).
        mergedTarget.initialBalanceMinor.shouldBe(780)
        accountAdjustmentRepository.findById(adjustment.id!!).get().trackingAccountId.shouldBe(target.id)
        recurringTransactionRuleRepository.findById(rule.id!!).get().trackingAccountId.shouldBe(target.id)
        fundsTransferRepository.findByUserIdOrderByDateDesc(alice.id!!).shouldBe(emptyList())
    }

    @Test
    fun rejectsInvalidTrackingAccountMerges() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 100, isDefault = true)
        val euro = saveAccount(alice, "Euro", "EUR", 0, isDefault = false)
        val bobAccount = saveAccount(bob, "Bob account", "AUD", 0, isDefault = true)
        val token = api().login("alice", "password")

        api().get("/api/tracking/accounts/${main.id}/merge-summary", null).statusCode().shouldBe(401)
        api().get("/api/tracking/accounts/${bobAccount.id}/merge-summary", token).statusCode().shouldBe(404)
        api().postJson(
            "/api/tracking/accounts/${main.id}/merge",
            """{"targetAccountId":${main.id}}""",
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/accounts/${main.id}/merge",
            """{"targetAccountId":${euro.id}}""",
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/accounts/${bobAccount.id}/merge",
            """{"targetAccountId":${main.id}}""",
            token,
        ).statusCode().shouldBe(404)
        api().postJson(
            "/api/tracking/accounts/${main.id}/merge",
            """{"targetAccountId":${bobAccount.id}}""",
            token,
        ).statusCode().shouldBe(400)
    }

    @Test
    fun createsDefaultAccountForNewRegularUser() {
        saveUser("admin", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        val response = api().postJson(
            "/api/users",
            """
                {"username":"alice","type":"USER"}
            """.trimIndent(),
            adminToken,
        )

        response.statusCode().shouldBe(201)
        val alice = userRepository.findByUsername("alice")!!
        val accounts = trackingAccountRepository.findByUserIdOrderByName(alice.id!!)
        accounts.size.shouldBe(1)
        accounts.single().name.shouldBe("Main")
        accounts.single().currency.shouldBe("AUD")
        accounts.single().initialBalanceMinor.shouldBe(0)
        accounts.single().isDefault.shouldBe(true)
    }

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = type,
        ),
    )

    private fun saveAccount(
        user: User,
        name: String,
        currency: String,
        initialBalanceMinor: Long,
        isDefault: Boolean,
        archived: Boolean = false,
    ): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = currency,
            initialBalanceMinor = initialBalanceMinor,
            isDefault = isDefault,
            archived = archived,
        ),
    )

    private fun saveExpenseCategory(user: User): ExpenseCategory = expenseCategoryRepository.save(
        ExpenseCategory(userId = user.id!!, name = "General"),
    )

    private fun saveIncomeCategory(user: User): IncomeCategory = incomeCategoryRepository.save(
        IncomeCategory(userId = user.id!!, name = "General"),
    )

    private fun saveTransaction(
        user: User,
        account: TrackingAccount,
        category: Any,
        type: TransactionType,
        amountMinor: Long,
    ): Transaction = transactionRepository.save(
        Transaction(
            userId = user.id!!,
            type = type,
            trackingAccountId = account.id!!,
            categoryId = when (category) {
                is ExpenseCategory -> category.id!!
                is IncomeCategory -> category.id!!
                else -> error("Unsupported category")
            },
            date = LocalDate.parse("2026-06-01"),
            amountMinor = amountMinor,
        ),
    )

    private fun saveTransfer(
        user: User,
        sourceAccount: TrackingAccount,
        targetAccount: TrackingAccount,
        sourceAmountMinor: Long,
        targetAmountMinor: Long,
    ): FundsTransfer = fundsTransferRepository.save(
        FundsTransfer(
            userId = user.id!!,
            sourceAccountId = sourceAccount.id!!,
            targetAccountId = targetAccount.id!!,
            sourceAmountMinor = sourceAmountMinor,
            targetAmountMinor = targetAmountMinor,
            date = LocalDate.parse("2026-06-01"),
        ),
    )
}
