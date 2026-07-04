package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestTimeProvider
import io.orangebuffalo.renalo.tracking.AccountAdjustment
import io.orangebuffalo.renalo.tracking.AccountAdjustmentRepository
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository

import io.orangebuffalo.renalo.tracking.IncomeCategory
import io.orangebuffalo.renalo.tracking.IncomeCategoryRepository
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
class AccountAdjustmentApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var incomeCategoryRepository: IncomeCategoryRepository

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var accountAdjustmentRepository: AccountAdjustmentRepository

    @Test
    fun requiresRegularUserForAdjustments() {
        val alice = saveUser("alice", UserType.USER)
        val admin = saveUser("admin", UserType.ADMIN)
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)

        val userToken = api().login("alice", "password")
        val adminToken = api().login("admin", "password")

        api().get("/api/tracking/accounts/${main.id}/adjustments", null).statusCode().shouldBe(401)
        api().get("/api/tracking/accounts/${main.id}/adjustments", adminToken).statusCode().shouldBe(403)
        api().get("/api/tracking/accounts/${main.id}/adjustments", userToken).statusCode().shouldBe(200)
    }

    @Test
    fun listsAdjustmentsWithBalanceForOwnAccount() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 10_000, isDefault = true)
        val expenseCategory = saveExpenseCategory(alice)
        val incomeCategory = saveIncomeCategory(alice)
        saveTransaction(alice, main, incomeCategory, TransactionType.INCOME, 5_000)
        saveTransaction(alice, main, expenseCategory, TransactionType.EXPENSE, 2_000)
        val adjustment = saveAdjustment(alice, main, 1_500)
        saveAdjustment(alice, main, -500)

        val response = api().get(
            "/api/tracking/accounts/${main.id}/adjustments",
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "accountId": ${main.id},
                  "accountName": "Main",
                  "currency": "AUD",
                  "currentBalanceMinor": 14000,
                  "adjustments": [
                    {
                      "id": ${adjustment.id!! + 1},
                      "adjustmentAmountMinor": -500
                    },
                    {
                      "id": ${adjustment.id},
                      "adjustmentAmountMinor": 1500
                    }
                  ]
                }
            """.trimIndent(),
        )
    }

    @Test
    fun returnsNotFoundForOtherUsersAccount() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val bobAccount = saveAccount(bob, "Bob", "AUD", 0, isDefault = true)
        val token = api().login("alice", "password")

        api().get("/api/tracking/accounts/${bobAccount.id}/adjustments", token).statusCode().shouldBe(404)
    }

    @Test
    fun createsAdjustmentForOwnAccount() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 10_000, isDefault = true)

        val response = api().postJson(
            "/api/tracking/accounts/${main.id}/adjustments",
            """{"adjustmentAmountMinor": 2500}""",
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(201)
        val saved = accountAdjustmentRepository.findByUserIdAndTrackingAccountIdOrderByIdDesc(alice.id!!, main.id!!)
        saved.size.shouldBe(1)
        saved.single().adjustmentAmountMinor.shouldBe(2500)
    }

    @Test
    fun rejectsZeroAdjustment() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)

        val response = api().postJson(
            "/api/tracking/accounts/${main.id}/adjustments",
            """{"adjustmentAmountMinor": 0}""",
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(400)
    }

    @Test
    fun createsNegativeAdjustment() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 10_000, isDefault = true)

        val response = api().postJson(
            "/api/tracking/accounts/${main.id}/adjustments",
            """{"adjustmentAmountMinor": -3500}""",
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(201)
        val saved = accountAdjustmentRepository.findByUserIdAndTrackingAccountIdOrderByIdDesc(alice.id!!, main.id!!)
        saved.single().adjustmentAmountMinor.shouldBe(-3500)

        val listResponse = api().get(
            "/api/tracking/accounts/${main.id}/adjustments",
            api().login("alice", "password"),
        )
        listResponse.body().shouldEqualJson(
            """
                {
                  "accountId": ${main.id},
                  "accountName": "Main",
                  "currency": "AUD",
                  "currentBalanceMinor": 6500,
                  "adjustments": [
                    {
                      "id": ${saved.single().id},
                      "adjustmentAmountMinor": -3500
                    }
                  ]
                }
            """.trimIndent(),
        )
    }

    @Test
    fun deletesOwnAdjustment() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        val adjustment = saveAdjustment(alice, main, 1000)

        val deleteResponse = api().delete(
            "/api/tracking/accounts/${main.id}/adjustments/${adjustment.id}",
            api().login("alice", "password"),
        )

        deleteResponse.statusCode().shouldBe(204)
        accountAdjustmentRepository.findByIdAndUserId(adjustment.id!!, alice.id!!).shouldBe(null)
    }

    @Test
    fun protectsOtherUsersAdjustments() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val aliceMain = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        val bobMain = saveAccount(bob, "Bob", "AUD", 0, isDefault = true)
        val bobAdjustment = saveAdjustment(bob, bobMain, 500)
        val aliceToken = api().login("alice", "password")
        val bobToken = api().login("bob", "password")

        api().get("/api/tracking/accounts/${bobMain.id}/adjustments", aliceToken).statusCode().shouldBe(404)
        api().postJson(
            "/api/tracking/accounts/${bobMain.id}/adjustments",
            """{"adjustmentAmountMinor": 100}""",
            aliceToken,
        ).statusCode().shouldBe(404)
        api().delete(
            "/api/tracking/accounts/${aliceMain.id}/adjustments/${bobAdjustment.id}",
            aliceToken,
        ).statusCode().shouldBe(404)
        api().delete(
            "/api/tracking/accounts/${bobMain.id}/adjustments/${bobAdjustment.id}",
            bobToken,
        ).statusCode().shouldBe(204)
    }

    @Test
    fun adjustmentsAffectDashboardBalance() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 10_000, isDefault = true)
        val expenseCategory = saveExpenseCategory(alice)
        val incomeCategory = saveIncomeCategory(alice)
        saveTransaction(alice, main, incomeCategory, TransactionType.INCOME, 5_000, TestTimeProvider.DEFAULT_DATE)
        saveTransaction(alice, main, expenseCategory, TransactionType.EXPENSE, 2_000, TestTimeProvider.DEFAULT_DATE)
        saveAdjustment(alice, main, 1_500)
        saveAdjustment(alice, main, -500)

        val response = api().get("/api/tracking/dashboard/accounts", api().login("alice", "password"))

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                [
                  {
                    "accountId": ${main.id},
                    "accountName": "Main",
                    "currency": "AUD",
                    "totalBalanceMinor": 14000,
                    "currentMonthInflowMinor": 5000,
                    "currentMonthOutflowMinor": 2000
                  }
                ]
            """.trimIndent(),
        )
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
        isDefault: Boolean = false,
    ): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = currency,
            initialBalanceMinor = initialBalanceMinor,
            isDefault = isDefault,
        ),
    )

    private fun saveExpenseCategory(user: User): ExpenseCategory = expenseCategoryRepository.save(
        ExpenseCategory(userId = user.id!!, name = "General"),
    )

    private fun saveIncomeCategory(user: User): IncomeCategory = incomeCategoryRepository.save(
        IncomeCategory(userId = user.id!!, name = "Salary"),
    )

    private fun saveTransaction(
        user: User,
        account: TrackingAccount,
        category: Any,
        type: TransactionType,
        amountMinor: Long,
        date: LocalDate = TestTimeProvider.DEFAULT_DATE,
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
            date = date,
            amountMinor = amountMinor,
        ),
    )

    private fun saveAdjustment(
        user: User,
        account: TrackingAccount,
        amountMinor: Long,
    ): AccountAdjustment = accountAdjustmentRepository.save(
        AccountAdjustment(
            userId = user.id!!,
            trackingAccountId = account.id!!,
            adjustmentAmountMinor = amountMinor,
        ),
    )

}
