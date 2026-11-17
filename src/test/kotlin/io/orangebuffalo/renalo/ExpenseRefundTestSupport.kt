package io.orangebuffalo.renalo

import com.fasterxml.jackson.databind.ObjectMapper
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestAuthTokens
import io.orangebuffalo.renalo.test.TestTimeProvider
import io.orangebuffalo.renalo.tracking.*
import io.orangebuffalo.renalo.user.*
import jakarta.inject.Inject
import java.time.LocalDate

abstract class ExpenseRefundTestSupport : IntegrationTestSupport() {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var accounts: TrackingAccountRepository
    @Inject lateinit var categories: ExpenseCategoryRepository
    @Inject lateinit var transactions: TransactionRepository
    @Inject lateinit var rules: RecurringTransactionRuleRepository
    @Inject lateinit var skips: RecurringTransactionSkipRepository
    @Inject lateinit var generation: RecurringTransactionGenerationService
    @Inject lateinit var tokens: TestAuthTokens
    val mapper = ObjectMapper()

    val today: LocalDate = TestTimeProvider.DEFAULT_DATE

    fun fixture(name: String = "alice"): RefundFixture {
        val user = users.save(User(username = name, passwordHash = "unused", type = UserType.USER))
        val account = accounts.save(TrackingAccount(userId = user.id!!, name = "Main", currency = "AUD", initialBalanceMinor = 0, isDefault = true))
        val category = categories.save(ExpenseCategory(userId = user.id!!, name = "Shopping"))
        return RefundFixture(user, account, category)
    }

    fun expense(f: RefundFixture, amount: Long = 12000, date: LocalDate = today, rule: RecurringTransactionRule? = null): Transaction =
        transactions.save(Transaction(userId = f.user.id!!, type = TransactionType.EXPENSE, trackingAccountId = f.account.id!!,
            categoryId = f.category.id!!, date = date, amountMinor = amount, notes = "Returned purchase", recurringRuleId = rule?.id,
            recurringInstanceDate = rule?.let { date }))

    fun rule(f: RefundFixture): RecurringTransactionRule = rules.save(RecurringTransactionRule(userId = f.user.id!!,
        transactionType = TransactionType.EXPENSE, trackingAccountId = f.account.id!!, categoryId = f.category.id!!,
        startDate = today.minusYears(1), recurrenceFrequency = 1, recurrenceInterval = RecurrenceInterval.MONTH,
        generatedUntil = today.plusMonths(1), amountMinor = 6000))

    fun token(f: RefundFixture) = tokens.issueToken(f.user.username, UserType.USER)
    fun query(amount: Long = 12000, period: String = "SIX_MONTHS", currency: String = "AUD") =
        """{"currency":"$currency","amountMinor":$amount,"period":"$period"}"""

    fun saveBody(search: String, match: com.fasterxml.jackson.databind.JsonNode) =
        """{"search":$search,"transactionId":${match["transactionId"]},"kind":${match["kind"]},"fingerprint":${match["fingerprint"]}}"""
}

data class RefundFixture(val user: User, val account: TrackingAccount, val category: ExpenseCategory)
