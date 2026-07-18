package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestTimeProvider
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
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import java.time.LocalDate
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class DashboardApiTest : IntegrationTestSupport() {
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
    lateinit var fundsTransferRepository: FundsTransferRepository

    @Test
    fun requiresRegularUserForDashboardAccountSummaries() {
        saveUser("alice", UserType.USER)
        saveUser("admin", UserType.ADMIN)

        api().get("/api/tracking/dashboard/accounts", null).statusCode().shouldBe(401)
        api().get("/api/tracking/dashboard/accounts", api().login("admin", "password")).statusCode().shouldBe(403)
        api().get("/api/tracking/dashboard/accounts", api().login("alice", "password")).statusCode().shouldBe(200)
    }

    @Test
    fun summarizesAccountBalancesAndMonthToDateMoneyFlow() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 10_000, isDefault = true)
        val savings = saveAccount(alice, "Savings", "AUD", 50_000)
        val oldAccount = saveAccount(alice, "Old", "AUD", 1_000)
        val dormant = saveAccount(alice, "Dormant", "AUD", 777)
        val archived = saveAccount(alice, "Archived", "AUD", 9_999, archived = true)
        val hidden = saveAccount(bob, "Hidden", "AUD", 999_999, isDefault = true)
        val hiddenSavings = saveAccount(bob, "Hidden savings", "AUD", 999_999)
        val expenseCategory = saveExpenseCategory(alice, "General")
        val incomeCategory = saveIncomeCategory(alice, "Salary")
        val bobExpenseCategory = saveExpenseCategory(bob, "Hidden")
        val bobIncomeCategory = saveIncomeCategory(bob, "Hidden")

        saveTransaction(alice, main, incomeCategory, TransactionType.INCOME, TestTimeProvider.DEFAULT_DATE, 20_000)
        saveTransaction(alice, main, incomeCategory, TransactionType.INCOME, TestTimeProvider.DEFAULT_DATE.minusMonths(1), 3_000)
        saveTransaction(alice, main, incomeCategory, TransactionType.INCOME, TestTimeProvider.DEFAULT_DATE.plusDays(1), 99_000)
        saveTransaction(alice, main, expenseCategory, TransactionType.EXPENSE, TestTimeProvider.DEFAULT_DATE, 4_000)
        saveTransaction(alice, main, expenseCategory, TransactionType.EXPENSE, TestTimeProvider.DEFAULT_DATE.minusMonths(1), 1_000)
        saveTransaction(alice, main, expenseCategory, TransactionType.EXPENSE, TestTimeProvider.DEFAULT_DATE.plusDays(1), 88_000)
        saveTransaction(alice, oldAccount, expenseCategory, TransactionType.EXPENSE, TestTimeProvider.DEFAULT_DATE.minusMonths(1), 200)
        saveTransaction(alice, archived, incomeCategory, TransactionType.INCOME, TestTimeProvider.DEFAULT_DATE, 50_000)
        saveTransaction(bob, hidden, bobIncomeCategory, TransactionType.INCOME, TestTimeProvider.DEFAULT_DATE, 777_777)
        saveTransaction(bob, hidden, bobExpenseCategory, TransactionType.EXPENSE, TestTimeProvider.DEFAULT_DATE, 111_111)

        saveTransfer(alice, main, savings, TestTimeProvider.DEFAULT_DATE, 7_000, 7_000)
        saveTransfer(alice, savings, main, TestTimeProvider.DEFAULT_DATE.minusMonths(1), 2_000, 2_000)
        saveTransfer(alice, main, savings, TestTimeProvider.DEFAULT_DATE.plusDays(1), 12_000, 12_000)
        saveTransfer(bob, hidden, hiddenSavings, TestTimeProvider.DEFAULT_DATE, 123, 123)

        val response = api().get("/api/tracking/dashboard/accounts", api().login("alice", "password"))

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                [
                  {
                    "accountId": ${main.id},
                    "accountName": "Main",
                    "currency": "AUD",
                    "totalBalanceMinor": 23000,
                    "currentMonthInflowMinor": 20000,
                    "currentMonthOutflowMinor": 11000
                  },
                  {
                    "accountId": ${savings.id},
                    "accountName": "Savings",
                    "currency": "AUD",
                    "totalBalanceMinor": 55000,
                    "currentMonthInflowMinor": 7000,
                    "currentMonthOutflowMinor": 0
                  },
                  {
                    "accountId": ${oldAccount.id},
                    "accountName": "Old",
                    "currency": "AUD",
                    "totalBalanceMinor": 800,
                    "currentMonthInflowMinor": 0,
                    "currentMonthOutflowMinor": 0
                  },
                  {
                    "accountId": ${dormant.id},
                    "accountName": "Dormant",
                    "currency": "AUD",
                    "totalBalanceMinor": 777,
                    "currentMonthInflowMinor": 0,
                    "currentMonthOutflowMinor": 0
                  }
                ]
            """.trimIndent(),
        )
    }

    @Test
    fun usesEachTransferSidesAmountForCrossCurrencyBalancesAndFlows() {
        val alice = saveUser("alice", UserType.USER)
        val aud = saveAccount(alice, "AUD", "AUD", 100_000, isDefault = true)
        val jpy = saveAccount(alice, "JPY", "JPY", 50_000)
        saveTransfer(alice, aud, jpy, TestTimeProvider.DEFAULT_DATE, 12_345, 9_876)
        saveTransfer(alice, jpy, aud, TestTimeProvider.DEFAULT_DATE.plusDays(1), 7_000, 60)

        val response = api().get("/api/tracking/dashboard/accounts", api().login("alice", "password"))

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                [
                  {
                    "accountId": ${aud.id},
                    "accountName": "AUD",
                    "currency": "AUD",
                    "totalBalanceMinor": 87655,
                    "currentMonthInflowMinor": 0,
                    "currentMonthOutflowMinor": 12345
                  },
                  {
                    "accountId": ${jpy.id},
                    "accountName": "JPY",
                    "currency": "JPY",
                    "totalBalanceMinor": 59876,
                    "currentMonthInflowMinor": 9876,
                    "currentMonthOutflowMinor": 0
                  }
                ]
            """.trimIndent(),
        )
    }

    @Test
    fun calculatesCurrentBalanceAndMonthInTheClientTimezone() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 10_000, isDefault = true)
        val incomeCategory = saveIncomeCategory(alice, "Salary")
        saveTransaction(alice, main, incomeCategory, TransactionType.INCOME, TestTimeProvider.DEFAULT_DATE.minusDays(1), 2_000)
        saveTransaction(alice, main, incomeCategory, TransactionType.INCOME, TestTimeProvider.DEFAULT_DATE, 3_000)
        val token = api().login("alice", "password")

        val honoluluResponse = api().get("/api/tracking/dashboard/accounts", token, "Pacific/Honolulu")
        val utcResponse = api().get("/api/tracking/dashboard/accounts", token, "UTC")

        honoluluResponse.statusCode().shouldBe(200)
        honoluluResponse.body().shouldEqualJson(
            """
                [{
                  "accountId": ${main.id},
                  "accountName": "Main",
                  "currency": "AUD",
                  "totalBalanceMinor": 12000,
                  "currentMonthInflowMinor": 2000,
                  "currentMonthOutflowMinor": 0
                }]
            """.trimIndent(),
        )
        utcResponse.statusCode().shouldBe(200)
        utcResponse.body().shouldEqualJson(
            """
                [{
                  "accountId": ${main.id},
                  "accountName": "Main",
                  "currency": "AUD",
                  "totalBalanceMinor": 15000,
                  "currentMonthInflowMinor": 5000,
                  "currentMonthOutflowMinor": 0
                }]
            """.trimIndent(),
        )
        api().get("/api/tracking/dashboard/accounts", token, "not-a-timezone").statusCode().shouldBe(400)
    }

    private fun saveUser(username: String, type: UserType): User =
        userRepository.save(User(username = username, passwordHash = passwordHasher.hash("password"), type = type))

    private fun saveAccount(
        user: User,
        name: String,
        currency: String,
        initialBalanceMinor: Long,
        isDefault: Boolean = false,
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

    private fun saveExpenseCategory(user: User, name: String): ExpenseCategory =
        expenseCategoryRepository.save(ExpenseCategory(userId = user.id!!, name = name))

    private fun saveIncomeCategory(user: User, name: String): IncomeCategory =
        incomeCategoryRepository.save(IncomeCategory(userId = user.id!!, name = name))

    private fun saveTransaction(
        user: User,
        account: TrackingAccount,
        category: Any,
        type: TransactionType,
        date: LocalDate,
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
            date = date,
            amountMinor = amountMinor,
            notes = null,
        ),
    )

    private fun saveTransfer(
        user: User,
        sourceAccount: TrackingAccount,
        targetAccount: TrackingAccount,
        date: LocalDate,
        sourceAmountMinor: Long,
        targetAmountMinor: Long,
    ): FundsTransfer = fundsTransferRepository.save(
        FundsTransfer(
            userId = user.id!!,
            sourceAccountId = sourceAccount.id!!,
            targetAccountId = targetAccount.id!!,
            sourceAmountMinor = sourceAmountMinor,
            targetAmountMinor = targetAmountMinor,
            date = date,
        ),
    )
}
