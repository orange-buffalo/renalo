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
import org.junit.jupiter.api.Test
import java.time.LocalDate

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class NetWorthAnalyticsApiTest : IntegrationTestSupport() {
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

    @Inject
    lateinit var accountAdjustmentRepository: AccountAdjustmentRepository

    @Test
    fun requiresRegularUserForNetWorthAnalytics() {
        saveUser("alice", UserType.USER)
        saveUser("admin", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        api().get("/api/tracking/analytics/net-worth/time-series", null).statusCode().shouldBe(401)
        api().get("/api/tracking/analytics/net-worth/time-series", adminToken).statusCode().shouldBe(403)
    }

    @Test
    fun calculatesNetWorthAcrossAllAccountsInDefaultCurrency() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val main = saveAccount(alice, "Main", "AUD", 10_000, isDefault = true)
        val usd = saveAccount(alice, "Archived USD", "USD", 10_000, archived = true)
        val expenseCategory = expenseCategoryRepository.save(ExpenseCategory(userId = alice.id!!, name = "General"))
        val incomeCategory = incomeCategoryRepository.save(IncomeCategory(userId = alice.id!!, name = "Salary"))

        saveTransfer(alice, main, usd, "2099-01-01", 1_000, 700)
        saveTransaction(alice, main, incomeCategory.id!!, TransactionType.INCOME, "2099-01-01", 1_000, 1_000)
        saveTransaction(alice, usd, expenseCategory.id!!, TransactionType.EXPENSE, "2099-01-02", 100, 150)
        saveTransaction(alice, usd, expenseCategory.id!!, TransactionType.EXPENSE, "2099-01-04", 100, 200)
        saveAdjustment(alice, usd, "2099-01-03", 200)
        saveAdjustment(alice, usd, "2099-01-04", 200)
        saveTransaction(
            alice,
            main,
            incomeCategory.id!!,
            TransactionType.INCOME,
            TestTimeProvider.DEFAULT_DATE.plusDays(1).toString(),
            99_999,
            99_999,
        )

        val bobAccount = saveAccount(bob, "Bob account", "AUD", 999_999, isDefault = true)
        val bobIncome = incomeCategoryRepository.save(IncomeCategory(userId = bob.id!!, name = "Hidden"))
        saveTransaction(bob, bobAccount, bobIncome.id!!, TransactionType.INCOME, "2099-01-01", 999_999, 999_999)

        val token = api().login("alice", "password")
        val response = api().get(
            "/api/tracking/analytics/net-worth/time-series" +
                "?from=2099-01-01&to=2099-01-04&granularity=DAY",
            token,
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "granularity": "DAY",
                  "from": "2099-01-01",
                  "to": "2099-01-04",
                  "points": [
                    { "bucket": "2099-01-01", "currency": "AUD", "amountMinor": 26000 },
                    { "bucket": "2099-01-02", "currency": "AUD", "amountMinor": 25850 },
                    { "bucket": "2099-01-03", "currency": "AUD", "amountMinor": 26150 },
                    { "bucket": "2099-01-04", "currency": "AUD", "amountMinor": 26350 }
                  ]
                }
            """.trimIndent(),
        )

        val cappedResponse = api().get(
            "/api/tracking/analytics/net-worth/time-series" +
                "?from=${TestTimeProvider.DEFAULT_DATE}&to=${TestTimeProvider.DEFAULT_DATE.plusDays(2)}&granularity=DAY",
            token,
        )
        cappedResponse.statusCode().shouldBe(200)
        cappedResponse.body().shouldEqualJson(
            """
                {
                  "granularity": "DAY",
                  "from": "${TestTimeProvider.DEFAULT_DATE}",
                  "to": "${TestTimeProvider.DEFAULT_DATE}",
                  "points": [
                    {
                      "bucket": "${TestTimeProvider.DEFAULT_DATE}",
                      "currency": "AUD",
                      "amountMinor": 26350
                    }
                  ]
                }
            """.trimIndent(),
        )
    }

    @Test
    fun validatesDateRangeAndTimeZone() {
        val alice = saveUser("alice", UserType.USER)
        saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        val token = api().login("alice", "password")

        api().get(
            "/api/tracking/analytics/net-worth/time-series?from=2099-02-02&to=2099-02-01",
            token,
        ).statusCode().shouldBe(400)
        api().get(
            "/api/tracking/analytics/net-worth/time-series",
            token,
            "not-a-time-zone",
        ).statusCode().shouldBe(400)
    }

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(username = username, passwordHash = passwordHasher.hash("password"), type = type),
    )

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

    private fun saveTransaction(
        user: User,
        account: TrackingAccount,
        categoryId: Long,
        type: TransactionType,
        date: String,
        amountMinor: Long,
        defaultCurrencyAmountMinor: Long,
    ): Transaction = transactionRepository.save(
        Transaction(
            userId = user.id!!,
            type = type,
            trackingAccountId = account.id!!,
            categoryId = categoryId,
            date = LocalDate.parse(date),
            amountMinor = amountMinor,
            defaultCurrencyAmountMinor = defaultCurrencyAmountMinor,
            defaultCurrency = "AUD",
        ),
    )

    private fun saveTransfer(
        user: User,
        source: TrackingAccount,
        target: TrackingAccount,
        date: String,
        sourceAmountMinor: Long,
        targetAmountMinor: Long,
    ): FundsTransfer = fundsTransferRepository.save(
        FundsTransfer(
            userId = user.id!!,
            sourceAccountId = source.id!!,
            targetAccountId = target.id!!,
            sourceAmountMinor = sourceAmountMinor,
            targetAmountMinor = targetAmountMinor,
            date = LocalDate.parse(date),
        ),
    )

    private fun saveAdjustment(user: User, account: TrackingAccount, date: String, amountMinor: Long) {
        accountAdjustmentRepository.save(
            AccountAdjustment(
                userId = user.id!!,
                trackingAccountId = account.id!!,
                adjustmentAmountMinor = amountMinor,
                date = LocalDate.parse(date),
            ),
        )
    }
}
