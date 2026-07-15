package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.IncomeCategory
import io.orangebuffalo.renalo.tracking.IncomeCategoryRepository
import io.orangebuffalo.renalo.tracking.RecurringTransactionRuleRepository
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.tracking.Transaction
import io.orangebuffalo.renalo.tracking.TransactionRepository
import io.orangebuffalo.renalo.tracking.TransactionType
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.LocalDate

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class IncomeApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var incomeCategoryRepository: IncomeCategoryRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var recurringTransactionRuleRepository: RecurringTransactionRuleRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun requiresRegularUserForIncomes() {
        val alice = saveUser("alice", UserType.USER)
        val account = saveAccount(alice, "Main", "AUD")
        val category = saveIncomeCategory(alice, "Salary")
        saveUser("admin", UserType.ADMIN)
        val userToken = api().login("alice", "password")
        val adminToken = api().login("admin", "password")
        val body = transactionJson(account, category, "2026-06-15", 1234, "Pay")

        api().get("/api/tracking/transactions/INCOME", null).statusCode().shouldBe(401)
        api().get("/api/tracking/transactions/INCOME", adminToken).statusCode().shouldBe(403)
        api().postJson("/api/tracking/transactions/INCOME", body, null).statusCode().shouldBe(401)
        api().postJson("/api/tracking/transactions/INCOME", body, adminToken).statusCode().shouldBe(403)
        api().get("/api/tracking/transactions/INCOME", userToken).statusCode().shouldBe(200)
    }

    @Test
    fun createsUpdatesDeletesAndListsOnlyCurrentUsersIncomes() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD")
        val savings = saveAccount(alice, "Savings", "EUR")
        val salary = saveIncomeCategory(alice, "Salary")
        val bonus = saveIncomeCategory(alice, "Bonus")
        saveIncome(bob, saveAccount(bob, "Bob account", "USD"), saveIncomeCategory(bob, "Bob income"), "2026-06-15", 9999, "Hidden")
        val token = api().login("alice", "password")

        val createResponse = api().postJson(
            "/api/tracking/transactions/INCOME",
            transactionJson(main, salary, "2026-06-15", 123400, " Salary "),
            token,
        )

        createResponse.statusCode().shouldBe(201)
        val income = transactionRepository.findByUserIdAndTypeOrderByDateDesc(alice.id!!, TransactionType.INCOME).single()
        createResponse.body().shouldEqualJson(
            """
                {
                  "id": ${income.id},
                  "trackingAccount": {
                    "id": ${main.id},
                    "name": "Main",
                    "currency": "AUD"
                  },
                  "category": {
                    "id": ${salary.id},
                    "name": "Salary"
                  },
                  "date": "2026-06-15",
                  "amountMinor": 123400,
                  "notes": "Salary"
                }
            """.trimIndent(),
        )

        val updateResponse = api().patchJson(
            "/api/tracking/transactions/INCOME/${income.id}",
            transactionJson(savings, bonus, "2026-06-16", 25000, null),
            token,
        )

        updateResponse.statusCode().shouldBe(200)
        updateResponse.body().shouldEqualJson(
            """
                {
                  "id": ${income.id},
                  "trackingAccount": {
                    "id": ${savings.id},
                    "name": "Savings",
                    "currency": "EUR"
                  },
                  "category": {
                    "id": ${bonus.id},
                    "name": "Bonus"
                  },
                  "date": "2026-06-16",
                  "amountMinor": 25000
                }
            """.trimIndent(),
        )

        val listResponse = api().get("/api/tracking/transactions/INCOME", token)

        listResponse.statusCode().shouldBe(200)
        listResponse.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${income.id},
                    "trackingAccount": {
                      "id": ${savings.id},
                      "name": "Savings",
                      "currency": "EUR"
                    },
                    "category": {
                      "id": ${bonus.id},
                      "name": "Bonus"
                    },
                    "date": "2026-06-16",
                    "amountMinor": 25000
                  }
                ]
            """.trimIndent(),
        )
        api().get("/api/tracking/transactions/EXPENSE", token).body().shouldEqualJson("[]")

        api().delete("/api/tracking/transactions/INCOME/${income.id}", token).statusCode().shouldBe(204)
        transactionRepository.findById(income.id!!).isPresent.shouldBe(false)
    }

    @Test
    fun filtersIncomesByInclusiveDateRange() {
        val alice = saveUser("alice", UserType.USER)
        val account = saveAccount(alice, "Main", "AUD")
        val salary = saveIncomeCategory(alice, "Salary")
        saveIncome(alice, account, salary, "2026-05-31", 1100, "Previous")
        val first = saveIncome(alice, account, salary, "2026-06-01", 1200, "First")
        val middle = saveIncome(alice, account, salary, "2026-06-15", 3400, "Middle")
        val last = saveIncome(alice, account, salary, "2026-06-30", 5600, "Last")
        saveIncome(alice, account, salary, "2026-07-01", 7800, "Next")
        val token = api().login("alice", "password")

        val response = api().get("/api/tracking/transactions/INCOME?from=2026-06-01&to=2026-06-30", token)

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${last.id},
                    "trackingAccount": {
                      "id": ${account.id},
                      "name": "Main",
                      "currency": "AUD"
                    },
                    "category": {
                      "id": ${salary.id},
                      "name": "Salary"
                    },
                    "date": "2026-06-30",
                    "amountMinor": 5600,
                    "notes": "Last"
                  },
                  {
                    "id": ${middle.id},
                    "trackingAccount": {
                      "id": ${account.id},
                      "name": "Main",
                      "currency": "AUD"
                    },
                    "category": {
                      "id": ${salary.id},
                      "name": "Salary"
                    },
                    "date": "2026-06-15",
                    "amountMinor": 3400,
                    "notes": "Middle"
                  },
                  {
                    "id": ${first.id},
                    "trackingAccount": {
                      "id": ${account.id},
                      "name": "Main",
                      "currency": "AUD"
                    },
                    "category": {
                      "id": ${salary.id},
                      "name": "Salary"
                    },
                    "date": "2026-06-01",
                    "amountMinor": 1200,
                    "notes": "First"
                  }
                ]
            """.trimIndent(),
        )
    }

    @Test
    fun filtersIncomesByCategoryAccountAndNotesTokens() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD")
        val savings = saveAccount(alice, "Savings", "AUD")
        val salary = saveIncomeCategory(alice, "Salary")
        val bonus = saveIncomeCategory(alice, "Bonus")
        val matching = saveIncome(alice, main, salary, "2026-06-15", 1200, "Monthly consulting")
        saveIncome(alice, savings, salary, "2026-06-16", 3400, "Monthly consulting")
        saveIncome(alice, main, bonus, "2026-06-17", 5600, "Monthly consulting")
        saveIncome(alice, main, salary, "2026-06-18", 7800, "Monthly retainer")
        val token = api().login("alice", "password")

        val response = api().get(
            "/api/tracking/transactions/INCOME?categoryIds=${salary.id}&accountIds=${main.id}&notes=monthly%20consulting",
            token,
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${matching.id},
                    "trackingAccount": {
                      "id": ${main.id},
                      "name": "Main",
                      "currency": "AUD"
                    },
                    "category": {
                      "id": ${salary.id},
                      "name": "Salary"
                    },
                    "date": "2026-06-15",
                    "amountMinor": 1200,
                    "notes": "Monthly consulting"
                  }
                ]
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsIncomeReferencesOutsideIncomeCategoryScope() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val account = saveAccount(alice, "Main", "AUD")
        val aliceIncomeCategory = saveIncomeCategory(alice, "Salary")
        val bobIncomeCategory = saveIncomeCategory(bob, "Bob salary")
        expenseCategoryRepository.save(ExpenseCategory(userId = alice.id!!, name = "Groceries"))
        expenseCategoryRepository.save(ExpenseCategory(userId = alice.id!!, name = "Rent"))
        val expenseCategory = expenseCategoryRepository.save(ExpenseCategory(userId = alice.id!!, name = "Travel"))
        val token = api().login("alice", "password")

        api().postJson(
            "/api/tracking/transactions/INCOME",
            transactionJson(account, bobIncomeCategory, "2026-06-15", 1000, null),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/transactions/INCOME",
            transactionJson(account, expenseCategory.id!!, "2026-06-15", 1000, null),
            token,
        ).statusCode().shouldBe(400)

        val income = saveIncome(alice, account, aliceIncomeCategory, "2026-06-15", 1000, null)
        api().get("/api/tracking/transactions/EXPENSE/${income.id}", token).statusCode().shouldBe(404)
    }

    @Test
    fun createsRecurringIncomeTransactions() {
        val alice = saveUser("alice", UserType.USER)
        val account = saveAccount(alice, "Main", "AUD")
        val salary = saveIncomeCategory(alice, "Salary")
        val token = api().login("alice", "password")

        val response = api().postJson(
            "/api/tracking/transactions/INCOME",
            recurringTransactionJson(account, salary, "2026-06-15", 123400, "Salary", 2, "WEEK", "2026-07-15"),
            token,
        )

        response.statusCode().shouldBe(201)
        val rule = recurringTransactionRuleRepository.findAll().single()
        rule.transactionType.shouldBe(TransactionType.INCOME)
        rule.categoryId.shouldBe(salary.id)
        rule.recurrenceFrequency.shouldBe(2)
        rule.recurrenceInterval.shouldBe(RecurrenceInterval.WEEK)
        rule.endDate.shouldBe(LocalDate.parse("2026-07-15"))
        transactionRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!)
            .map { it.type to it.recurringInstanceDate }
            .shouldContainExactly(
                TransactionType.INCOME to LocalDate.parse("2026-06-15"),
                TransactionType.INCOME to LocalDate.parse("2026-06-29"),
                TransactionType.INCOME to LocalDate.parse("2026-07-13"),
            )
    }

    @Test
    fun rejectsNonpositiveIncomeAmountsAndAcceptsSmallestMinorUnit() {
        val alice = saveUser("alice", UserType.USER)
        val account = saveAccount(alice, "Main", "AUD")
        val salary = saveIncomeCategory(alice, "Salary")
        val token = api().login("alice", "password")

        listOf(0, -1).forEach { invalidAmount ->
            api().postJson(
                "/api/tracking/transactions/INCOME",
                transactionJson(account, salary, "2026-06-15", invalidAmount.toLong(), null),
                token,
            ).statusCode().shouldBe(400)
        }

        val response = api().postJson(
            "/api/tracking/transactions/INCOME",
            transactionJson(account, salary, "2026-06-15", 1, null),
            token,
        )
        response.statusCode().shouldBe(201)
        transactionRepository.findByUserIdAndTypeOrderByDateDesc(alice.id!!, TransactionType.INCOME)
            .single().amountMinor.shouldBe(1)
    }

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = type,
        ),
    )

    private fun saveAccount(user: User, name: String, currency: String): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = currency,
            initialBalanceMinor = 0,
            isDefault = name == "Main",
        ),
    )

    private fun saveIncomeCategory(user: User, name: String): IncomeCategory = incomeCategoryRepository.save(
        IncomeCategory(
            userId = user.id!!,
            name = name,
        ),
    )

    private fun saveIncome(
        user: User,
        account: TrackingAccount,
        category: IncomeCategory,
        date: String,
        amountMinor: Long,
        notes: String?,
    ): Transaction = transactionRepository.save(
        Transaction(
            userId = user.id!!,
            type = TransactionType.INCOME,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            date = LocalDate.parse(date),
            amountMinor = amountMinor,
            notes = notes,
        ),
    )

    private fun transactionJson(
        account: TrackingAccount,
        category: IncomeCategory,
        date: String,
        amountMinor: Long,
        notes: String?,
    ) = transactionJson(account, category.id!!, date, amountMinor, notes)

    private fun transactionJson(
        account: TrackingAccount,
        categoryId: Long,
        date: String,
        amountMinor: Long,
        notes: String?,
    ) = """
        {
          "trackingAccountId": ${account.id},
          "categoryId": $categoryId,
          "date": "$date",
          "amountMinor": $amountMinor,
          "notes": ${notes?.let { "\"$it\"" } ?: "null"}
        }
    """.trimIndent()

    private fun recurringTransactionJson(
        account: TrackingAccount,
        category: IncomeCategory,
        date: String,
        amountMinor: Long,
        notes: String?,
        frequency: Int,
        interval: String,
        endDate: String?,
    ) = """
        {
          "trackingAccountId": ${account.id},
          "categoryId": ${category.id},
          "date": "$date",
          "amountMinor": $amountMinor,
          "notes": ${notes?.let { "\"$it\"" } ?: "null"},
          "recurrence": {
            "frequency": $frequency,
            "interval": "$interval",
            "endDate": ${endDate?.let { "\"$it\"" } ?: "null"}
          }
        }
    """.trimIndent()
}
