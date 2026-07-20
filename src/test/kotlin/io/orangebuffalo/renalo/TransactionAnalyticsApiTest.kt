package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
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
class TransactionAnalyticsApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var incomeCategoryRepository: IncomeCategoryRepository

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun requiresRegularUserForTransactionAnalytics() {
        saveUser("alice", UserType.USER)
        saveUser("admin", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        api().get("/api/tracking/analytics/transactions/EXPENSE/time-series", null).statusCode().shouldBe(401)
        api().get("/api/tracking/analytics/transactions/EXPENSE/time-series", adminToken).statusCode().shouldBe(403)
    }

    @Test
    fun groupsFilteredExpensesByDayInDefaultCurrency() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", true)
        val usd = saveAccount(alice, "US account", "USD")
        val groceries = saveExpenseCategory(alice, "Groceries")
        val rent = saveExpenseCategory(alice, "Rent")
        val salary = saveIncomeCategory(alice, "Salary")
        saveTransaction(alice, TransactionType.EXPENSE, main, groceries.id!!, "2026-06-01", 1200, "Coffee beans")
        saveTransaction(alice, TransactionType.EXPENSE, main, groceries.id!!, "2026-06-01", 800, "BEANS and coffee")
        saveTransaction(
            alice,
            TransactionType.EXPENSE,
            usd,
            groceries.id!!,
            "2026-06-01",
            300,
            "Coffee beans",
            defaultCurrencyAmountMinor = 450,
        )
        saveTransaction(
            alice,
            TransactionType.EXPENSE,
            usd,
            groceries.id!!,
            "2026-06-01",
            700,
            "Coffee beans unavailable",
            defaultCurrencyAmountMinor = null,
        )
        saveTransaction(alice, TransactionType.EXPENSE, main, groceries.id!!, "2026-06-05", 400, "Coffee only")
        saveTransaction(alice, TransactionType.EXPENSE, main, rent.id!!, "2026-06-02", 500, "Coffee beans")
        saveTransaction(alice, TransactionType.INCOME, main, salary.id!!, "2026-06-01", 9999, "Coffee beans")
        val bobAccount = saveAccount(bob, "Bob account", "AUD", true)
        val bobCategory = saveExpenseCategory(bob, "Bob category")
        saveTransaction(bob, TransactionType.EXPENSE, bobAccount, bobCategory.id!!, "2026-06-01", 9999, "Coffee beans")
        val token = api().login("alice", "password")

        val response = api().get(
            "/api/tracking/analytics/transactions/EXPENSE/time-series" +
                "?from=2026-06-01&to=2026-06-10&categoryIds=${groceries.id}" +
                "&accountIds=${main.id},${usd.id}&notes=coffee%20beans",
            token,
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "granularity": "DAY",
                  "from": "2026-06-01",
                  "to": "2026-06-10",
                  "points": [
                    { "bucket": "2026-06-01", "currency": "AUD", "amountMinor": 2450 }
                  ]
                }
            """.trimIndent(),
        )
    }

    @Test
    fun selectsCalendarWeekAndMonthGranularities() {
        val alice = saveUser("alice", UserType.USER)
        val account = saveAccount(alice, "Main", "AUD", true)
        val foreignAccount = saveAccount(alice, "US account", "USD")
        val category = saveExpenseCategory(alice, "General")
        saveTransaction(
            alice,
            TransactionType.EXPENSE,
            foreignAccount,
            category.id!!,
            "2025-01-01",
            500,
            null,
            defaultCurrencyAmountMinor = null,
        )
        saveTransaction(
            alice,
            TransactionType.EXPENSE,
            foreignAccount,
            category.id!!,
            "2025-02-01",
            600,
            null,
            defaultCurrencyAmountMinor = 600,
            defaultCurrency = "USD",
        )
        saveTransaction(alice, TransactionType.EXPENSE, account, category.id!!, "2026-01-06", 100, null)
        saveTransaction(alice, TransactionType.EXPENSE, account, category.id!!, "2026-01-11", 200, null)
        saveTransaction(alice, TransactionType.EXPENSE, account, category.id!!, "2026-03-10", 300, null)
        saveTransaction(alice, TransactionType.EXPENSE, account, category.id!!, "2026-10-20", 400, null)
        val token = api().login("alice", "password")

        val weekly = api().get(
            "/api/tracking/analytics/transactions/EXPENSE/time-series?from=2026-01-05&to=2026-03-15",
            token,
        )
        weekly.statusCode().shouldBe(200)
        weekly.body().shouldEqualJson(
            """
                {
                  "granularity": "WEEK",
                  "from": "2026-01-05",
                  "to": "2026-03-15",
                  "points": [
                    { "bucket": "2026-01-05", "currency": "AUD", "amountMinor": 300 },
                    { "bucket": "2026-03-09", "currency": "AUD", "amountMinor": 300 }
                  ]
                }
            """.trimIndent(),
        )

        val monthly = api().get("/api/tracking/analytics/transactions/EXPENSE/time-series", token)
        monthly.statusCode().shouldBe(200)
        monthly.body().shouldEqualJson(
            """
                {
                  "granularity": "MONTH",
                  "from": "2026-01-06",
                  "to": "2026-10-20",
                  "points": [
                    { "bucket": "2026-01-01", "currency": "AUD", "amountMinor": 300 },
                    { "bucket": "2026-03-01", "currency": "AUD", "amountMinor": 300 },
                    { "bucket": "2026-10-01", "currency": "AUD", "amountMinor": 400 }
                  ]
                }
            """.trimIndent(),
        )
    }

    @Test
    fun supportsIncomeAnalyticsAndExplicitGranularity() {
        val alice = saveUser("alice", UserType.USER)
        val account = saveAccount(alice, "Main", "AUD", true)
        val category = saveIncomeCategory(alice, "Salary")
        saveTransaction(alice, TransactionType.INCOME, account, category.id!!, "2026-06-15", 120000, "Salary")
        saveTransaction(alice, TransactionType.INCOME, account, category.id!!, "2026-07-15", 130000, "Salary")
        val token = api().login("alice", "password")

        val response = api().get(
            "/api/tracking/analytics/transactions/INCOME/time-series" +
                "?from=2026-06-01&to=2026-07-31&granularity=MONTH",
            token,
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "granularity": "MONTH",
                  "from": "2026-06-01",
                  "to": "2026-07-31",
                  "points": [
                    { "bucket": "2026-06-01", "currency": "AUD", "amountMinor": 120000 },
                    { "bucket": "2026-07-01", "currency": "AUD", "amountMinor": 130000 }
                  ]
                }
            """.trimIndent(),
        )
    }

    @Test
    fun returnsEmptyPointsAndRejectsInvalidFilters() {
        val alice = saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        val response = api().get(
            "/api/tracking/analytics/transactions/EXPENSE/time-series?from=2026-06-01&to=2026-06-30",
            token,
        )
        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "granularity": "DAY",
                  "from": "2026-06-01",
                  "to": "2026-06-30",
                  "points": []
                }
            """.trimIndent(),
        )

        api().get(
            "/api/tracking/analytics/transactions/EXPENSE/time-series?from=2026-06-01",
            token,
        ).statusCode().shouldBe(400)
        api().get(
            "/api/tracking/analytics/transactions/EXPENSE/time-series?categoryIds=invalid",
            token,
        ).statusCode().shouldBe(400)
        api().get(
            "/api/tracking/analytics/transactions/EXPENSE/time-series?granularity=YEAR",
            token,
        ).statusCode().shouldBe(400)
    }

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(username = username, passwordHash = passwordHasher.hash("password"), type = type),
    )

    private fun saveAccount(
        user: User,
        name: String,
        currency: String,
        isDefault: Boolean = false,
    ): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = currency,
            initialBalanceMinor = 0,
            isDefault = isDefault,
        ),
    )

    private fun saveExpenseCategory(user: User, name: String): ExpenseCategory = expenseCategoryRepository.save(
        ExpenseCategory(userId = user.id!!, name = name),
    )

    private fun saveIncomeCategory(user: User, name: String): IncomeCategory = incomeCategoryRepository.save(
        IncomeCategory(userId = user.id!!, name = name),
    )

    private fun saveTransaction(
        user: User,
        type: TransactionType,
        account: TrackingAccount,
        categoryId: Long,
        date: String,
        amountMinor: Long,
        notes: String?,
        defaultCurrencyAmountMinor: Long? = amountMinor,
        defaultCurrency: String = "AUD",
    ): Transaction = transactionRepository.save(
        Transaction(
            userId = user.id!!,
            type = type,
            trackingAccountId = account.id!!,
            categoryId = categoryId,
            date = LocalDate.parse(date),
            amountMinor = amountMinor,
            defaultCurrencyAmountMinor = defaultCurrencyAmountMinor,
            defaultCurrency = defaultCurrency,
            notes = notes,
        ),
    )
}
