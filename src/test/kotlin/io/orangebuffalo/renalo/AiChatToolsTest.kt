package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.ai.AiChatModelToolCall
import io.orangebuffalo.renalo.ai.AiChatTools
import io.orangebuffalo.renalo.ai.AiChatToolExecutionContext
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.tracking.Transaction
import io.orangebuffalo.renalo.tracking.TransactionRepository
import io.orangebuffalo.renalo.tracking.TransactionType
import io.orangebuffalo.renalo.tracking.DefaultCurrencyConversionSource
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.LocalDate

@MicronautTest(transactional = false)
class AiChatToolsTest : IntegrationTestSupport() {
    @Inject lateinit var tools: AiChatTools
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var passwordHasher: PasswordHasher
    @Inject lateinit var trackingAccountRepository: TrackingAccountRepository
    @Inject lateinit var expenseCategoryRepository: ExpenseCategoryRepository
    @Inject lateinit var transactionRepository: TransactionRepository

    @Test
    fun exposesOnlyTheAuthenticatedUsersAuthoritativeFinancialData() {
        val alice = saveUser("alice")
        val bob = saveUser("bob")
        val aliceAccount = saveAccount(alice, "Daily", 12_345)
        saveAccount(bob, "Private", 999_999)
        val category = expenseCategoryRepository.save(ExpenseCategory(userId = alice.id!!, name = "Groceries"))
        transactionRepository.save(
            Transaction(
                userId = alice.id!!,
                type = TransactionType.EXPENSE,
                trackingAccountId = aliceAccount.id!!,
                categoryId = category.id!!,
                date = LocalDate.parse("2026-08-01"),
                amountMinor = 2_345,
                defaultCurrencyAmountMinor = 2_345,
                defaultCurrency = "AUD",
            ),
        )

        val context = AiChatToolExecutionContext()
        tools.execute(
            alice.id!!,
            LocalDate.parse("2026-08-01"),
            AiChatModelToolCall("call-1", "get_account_balances", "{}"),
        ).result.shouldEqualJson(
            """
                [{
                  "accountId": ${aliceAccount.id},
                  "accountName": "Daily",
                  "currency": "AUD",
                  "totalBalanceMinor": 10000,
                  "currentMonthInflowMinor": 0,
                  "currentMonthOutflowMinor": 2345
                }]
            """.trimIndent(),
        )
        val categoryResult = tools.execute(
            alice.id!!,
            LocalDate.parse("2026-08-01"),
            AiChatModelToolCall(
                "call-2",
                "get_category_totals",
                """{"type":"EXPENSE","from":"2026-08-01","to":"2026-08-01"}""",
            ),
            context,
        )
        categoryResult.result.shouldEqualJson(
            """
                [{
                  "categoryId": ${category.id},
                  "categoryName": "Groceries",
                  "currency": "AUD",
                  "amountMinor": 2345
                }]
            """.trimIndent(),
        )
        context.chartSources["call-2"] = categoryResult.chartSource!!
        val chartResult = tools.execute(
            alice.id!!,
            LocalDate.parse("2026-08-01"),
            AiChatModelToolCall(
                "call-chart",
                "present_chart",
                """{"kind":"DONUT","title":"Expenses by category"}""",
            ),
            context,
        )
        chartResult.chart?.kind.shouldBe(io.orangebuffalo.renalo.ai.AiChatChartKind.DONUT)
        chartResult.chart?.segments.shouldBe(
            listOf(io.orangebuffalo.renalo.ai.AiChatChartSegmentResponse("Groceries", "2345")),
        )

    }

    @Test
    fun exposesTheBoundedReadOnlyToolSet() {
        tools.specifications.map { it.name() }.shouldContainExactly(
            "get_account_balances",
            "get_category_totals",
            "search_transactions",
            "get_transaction_time_series",
            "get_net_worth",
            "search_transfers",
            "present_chart",
        )
    }

    @Test
    fun queriesAndSummarizesTransactionsAcrossTheCompleteUserBoundSet() {
        val alice = saveUser("alice")
        val bob = saveUser("bob")
        val aliceAccount = saveAccount(alice, "Brokerage", 0)
        val usdAccount = trackingAccountRepository.save(
            TrackingAccount(
                userId = alice.id!!,
                name = "USD investments",
                currency = "USD",
                initialBalanceMinor = 0,
                isDefault = false,
            ),
        )
        val bobAccount = saveAccount(bob, "Private", 0)
        val investment = expenseCategoryRepository.save(
            ExpenseCategory(userId = alice.id!!, name = "Investment", archived = true),
        )
        val groceries = expenseCategoryRepository.save(ExpenseCategory(userId = alice.id!!, name = "Groceries"))
        val bobCategory = expenseCategoryRepository.save(ExpenseCategory(userId = bob.id!!, name = "Investment"))

        saveExpense(alice, aliceAccount, investment, "2020-01-01", 100_000, "Largest contribution")
        transactionRepository.save(
            Transaction(
                userId = alice.id!!,
                type = TransactionType.EXPENSE,
                trackingAccountId = usdAccount.id!!,
                categoryId = investment.id!!,
                date = LocalDate.parse("2025-12-31"),
                amountMinor = 200_000,
                defaultCurrencyAmountMinor = 60,
                defaultCurrency = "AUD",
                defaultCurrencyConversionSource = DefaultCurrencyConversionSource.ACTUAL_TRANSFER,
                notes = "Converted investment",
            ),
        )
        (1L..51L).forEach { amount ->
            saveExpense(
                alice,
                aliceAccount,
                investment,
                LocalDate.parse("2026-01-01").plusDays(amount).toString(),
                amount,
                "Contribution $amount",
            )
        }
        saveExpense(alice, aliceAccount, groceries, "2026-08-01", 900_000, "Not an investment")
        saveExpense(bob, bobAccount, bobCategory, "2026-08-01", 800_000, "Private investment")
        transactionRepository.save(
            Transaction(
                userId = alice.id!!,
                type = TransactionType.EXPENSE,
                trackingAccountId = aliceAccount.id!!,
                categoryId = investment.id!!,
                date = LocalDate.parse("2026-08-01"),
                amountMinor = 700_000,
                notes = "Foreign amount without conversion evidence",
            ),
        )

        tools.execute(
            alice.id!!,
            LocalDate.parse("2026-08-01"),
            AiChatModelToolCall(
                "query",
                "search_transactions",
                """
                    {
                      "type":"EXPENSE",
                      "from":"",
                      "to":"",
                      "transactionIds":"",
                      "categoryIds":"",
                      "categoryNameQuery":"Investment",
                      "accountIds":"",
                      "accountNameQuery":"",
                      "notesQuery":"",
                      "currencies":"",
                      "minAmountMinor":"",
                      "maxAmountMinor":"",
                      "defaultCurrencies":"",
                      "minDefaultCurrencyAmountMinor":"",
                      "maxDefaultCurrencyAmountMinor":"",
                      "conversionSources":"",
                      "conversionTransferIds":"",
                      "recurring":"ANY",
                      "recurringRuleIds":"",
                      "recurringInstanceFrom":"",
                      "recurringInstanceTo":"",
                      "recurringLocked":"ANY",
                      "metadataSources":"",
                      "orderBy":"DEFAULT_CURRENCY_AMOUNT",
                      "direction":"DESC",
                      "offset":"0",
                      "limit":"2"
                    }
                """.trimIndent(),
            ),
        ).result.shouldEqualJson(
            """
                {
                  "items":[
                    {
                      "id":1,
                      "type":"EXPENSE",
                      "accountId":${aliceAccount.id},
                      "accountName":"Brokerage",
                      "currency":"AUD",
                      "categoryId":${investment.id},
                      "categoryName":"Investment",
                      "date":"2020-01-01",
                      "amountMinor":100000,
                      "defaultCurrencyAmountMinor":100000,
                      "defaultCurrency":"AUD",
                      "conversionSource":"SAME_CURRENCY",
                      "conversionTransferId":null,
                      "notes":"Largest contribution",
                      "metadata":null,
                      "recurringRuleId":null,
                      "recurringInstanceDate":null,
                      "recurringLocked":false
                    },
                    {
                      "id":2,
                      "type":"EXPENSE",
                      "accountId":${usdAccount.id},
                      "accountName":"USD investments",
                      "currency":"USD",
                      "categoryId":${investment.id},
                      "categoryName":"Investment",
                      "date":"2025-12-31",
                      "amountMinor":200000,
                      "defaultCurrencyAmountMinor":60,
                      "defaultCurrency":"AUD",
                      "conversionSource":"ACTUAL_TRANSFER",
                      "conversionTransferId":null,
                      "notes":"Converted investment",
                      "metadata":null,
                      "recurringRuleId":null,
                      "recurringInstanceDate":null,
                      "recurringLocked":false
                    }
                  ],
                  "offset":0,
                  "limit":2,
                  "hasMore":true,
                  "summary":{
                    "totalCount":54,
                    "defaultCurrency":"AUD",
                    "projectedCount":53,
                    "unprojectedCount":1,
                    "projectedAmountSumMinor":101386,
                    "projectedAmountMinMinor":1,
                    "projectedAmountMaxMinor":100000,
                    "projectedAmountAverageMinorRounded":1913
                  },
                  "originalCurrencySummaries":[
                    {
                      "currency":"AUD",
                      "transactionCount":53,
                      "amountSumMinor":801326,
                      "amountMinMinor":1,
                      "amountMaxMinor":700000,
                      "amountAverageMinorRounded":15119
                    },
                    {
                      "currency":"USD",
                      "transactionCount":1,
                      "amountSumMinor":200000,
                      "amountMinMinor":200000,
                      "amountMaxMinor":200000,
                      "amountAverageMinorRounded":200000
                    }
                  ]
                }
            """.trimIndent(),
        )
    }

    private fun saveUser(username: String): User = userRepository.save(
        User(username = username, passwordHash = passwordHasher.hash("password"), type = UserType.USER),
    )

    private fun saveAccount(user: User, name: String, balance: Long): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = "AUD",
            initialBalanceMinor = balance,
            isDefault = true,
        ),
    )

    private fun saveExpense(
        user: User,
        account: TrackingAccount,
        category: ExpenseCategory,
        date: String,
        amountMinor: Long,
        notes: String,
    ) = transactionRepository.save(
        Transaction(
            userId = user.id!!,
            type = TransactionType.EXPENSE,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            date = LocalDate.parse(date),
            amountMinor = amountMinor,
            defaultCurrencyAmountMinor = amountMinor,
            defaultCurrency = "AUD",
            defaultCurrencyConversionSource = DefaultCurrencyConversionSource.SAME_CURRENCY,
            notes = notes,
        ),
    )
}
