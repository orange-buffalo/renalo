package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.collections.shouldContainExactly
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.ai.AiChatModelToolCall
import io.orangebuffalo.renalo.ai.AiChatTools
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
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
        tools.execute(
            alice.id!!,
            LocalDate.parse("2026-08-01"),
            AiChatModelToolCall(
                "call-2",
                "get_category_totals",
                """{"type":"EXPENSE","from":"2026-08-01","to":"2026-08-01"}""",
            ),
        ).result.shouldEqualJson(
            """
                [{
                  "categoryId": ${category.id},
                  "categoryName": "Groceries",
                  "currency": "AUD",
                  "amountMinor": 2345
                }]
            """.trimIndent(),
        )
    }

    @Test
    fun exposesTheBoundedReadOnlyToolSet() {
        tools.specifications.map { it.name() }.shouldContainExactly(
            "get_account_balances",
            "get_category_totals",
            "search_transactions",
            "get_net_worth",
            "search_transfers",
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
}
