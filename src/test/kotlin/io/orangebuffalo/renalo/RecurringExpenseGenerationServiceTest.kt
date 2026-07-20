package io.orangebuffalo.renalo

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestTimeProvider
import io.orangebuffalo.renalo.tracking.DefaultCurrencyConversionSource
import io.orangebuffalo.renalo.tracking.Transaction
import io.orangebuffalo.renalo.tracking.TransactionType
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.TransactionRepository
import io.orangebuffalo.renalo.tracking.RecurringTransactionGenerationService
import io.orangebuffalo.renalo.tracking.RecurringTransactionRule
import io.orangebuffalo.renalo.tracking.RecurringTransactionRuleRepository
import io.orangebuffalo.renalo.tracking.RecurringTransactionSkip
import io.orangebuffalo.renalo.tracking.RecurringTransactionSkipRepository
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.LocalDate

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class RecurringTransactionGenerationServiceTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var expenseRepository: TransactionRepository

    @Inject
    lateinit var recurringExpenseRuleRepository: RecurringTransactionRuleRepository

    @Inject
    lateinit var recurringExpenseSkipRepository: RecurringTransactionSkipRepository

    @Inject
    lateinit var recurringExpenseGenerationService: RecurringTransactionGenerationService

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun generatesMissingExpensesThroughRuleEndDateAndUpdatesMetadata() {
        val alice = saveUser("alice")
        val account = saveAccount(alice)
        val category = saveCategory(alice)
        val rule = saveRecurringRule(
            user = alice,
            account = account,
            category = category,
            startDate = LocalDate.parse("2099-06-01"),
            endDate = LocalDate.parse("2099-06-29"),
            generatedUntil = LocalDate.parse("2099-05-31"),
        )

        val result = recurringExpenseGenerationService.generateForRule(rule)

        result.createdTransactions.shouldBe(5)
        result.updatedTransactions.shouldBe(0)
        result.skippedOccurrences.shouldBe(0)
        result.generatedUntil.shouldBe(LocalDate.parse("2099-06-29"))
        generatedDates(rule).shouldContainExactly(
            LocalDate.parse("2099-06-01"),
            LocalDate.parse("2099-06-08"),
            LocalDate.parse("2099-06-15"),
            LocalDate.parse("2099-06-22"),
            LocalDate.parse("2099-06-29"),
        )
        val updatedRule = recurringExpenseRuleRepository.findById(rule.id!!).get()
        updatedRule.generatedUntil.shouldBe(LocalDate.parse("2099-06-29"))
        updatedRule.lastGeneratedAt.shouldBe(TestTimeProvider.DEFAULT_TIME)
    }

    @Test
    fun isIdempotentAcrossRepeatedGenerationRuns() {
        val alice = saveUser("alice")
        val rule = saveRecurringRule(
            user = alice,
            startDate = LocalDate.parse("2099-06-01"),
            endDate = LocalDate.parse("2099-06-15"),
            generatedUntil = LocalDate.parse("2099-05-31"),
        )

        recurringExpenseGenerationService.generateForRule(rule).createdTransactions.shouldBe(3)
        val updatedRule = recurringExpenseRuleRepository.findById(rule.id!!).get()

        recurringExpenseGenerationService.generateForRule(updatedRule).createdTransactions.shouldBe(0)
        generatedDates(rule).shouldContainExactly(
            LocalDate.parse("2099-06-01"),
            LocalDate.parse("2099-06-08"),
            LocalDate.parse("2099-06-15"),
        )
    }

    @Test
    fun doesNotGenerateSkippedOccurrences() {
        val alice = saveUser("alice")
        val rule = saveRecurringRule(
            user = alice,
            startDate = LocalDate.parse("2099-06-01"),
            endDate = LocalDate.parse("2099-06-22"),
            generatedUntil = LocalDate.parse("2099-05-31"),
        )
        recurringExpenseSkipRepository.save(
            RecurringTransactionSkip(
                recurringRuleId = rule.id!!,
                recurringInstanceDate = LocalDate.parse("2099-06-15"),
            ),
        )

        val result = recurringExpenseGenerationService.generateForRule(rule)

        result.createdTransactions.shouldBe(3)
        result.skippedOccurrences.shouldBe(1)
        generatedDates(rule).shouldContainExactly(
            LocalDate.parse("2099-06-01"),
            LocalDate.parse("2099-06-08"),
            LocalDate.parse("2099-06-22"),
        )
    }

    @Test
    fun updatesUnlockedGeneratedExpensesButLeavesLockedExpensesUnchanged() {
        val alice = saveUser("alice")
        val account = saveAccount(alice)
        val category = saveCategory(alice)
        val rule = saveRecurringRule(
            user = alice,
            account = account,
            category = category,
            startDate = LocalDate.parse("2099-06-01"),
            endDate = LocalDate.parse("2099-06-08"),
            generatedUntil = LocalDate.parse("2099-05-31"),
            amountMinor = 2000,
            notes = "Updated rent",
        )
        val lockedExpense = saveGeneratedExpense(
            user = alice,
            account = account,
            category = category,
            rule = rule,
            instanceDate = LocalDate.parse("2099-06-01"),
            amountMinor = 1000,
            notes = "Locked rent",
            recurringLocked = true,
        )
        val unlockedExpense = saveGeneratedExpense(
            user = alice,
            account = account,
            category = category,
            rule = rule,
            instanceDate = LocalDate.parse("2099-06-08"),
            amountMinor = 1000,
            notes = "Old rent",
            recurringLocked = false,
        )

        val result = recurringExpenseGenerationService.generateForRule(rule)

        result.createdTransactions.shouldBe(0)
        result.updatedTransactions.shouldBe(1)
        val savedLockedExpense = expenseRepository.findById(lockedExpense.id!!).get()
        savedLockedExpense.amountMinor.shouldBe(1000)
        savedLockedExpense.notes.shouldBe("Locked rent")
        savedLockedExpense.recurringLocked.shouldBe(true)
        val savedUnlockedExpense = expenseRepository.findById(unlockedExpense.id!!).get()
        savedUnlockedExpense.amountMinor.shouldBe(2000)
        savedUnlockedExpense.notes.shouldBe("Updated rent")
        savedUnlockedExpense.recurringLocked.shouldBe(false)
    }

    @Test
    fun calculatesProjectionsOnlyForCreatedOrUpdatedOccurrences() {
        val alice = saveUser("alice")
        val account = saveAccount(alice)
        val category = saveCategory(alice)
        val unrelatedHistoricalExpense = expenseRepository.save(
            Transaction(
                userId = alice.id!!,
                type = TransactionType.EXPENSE,
                trackingAccountId = account.id!!,
                categoryId = category.id!!,
                date = LocalDate.parse("2020-01-01"),
                amountMinor = 500,
            ),
        )
        val rule = saveRecurringRule(
            user = alice,
            account = account,
            category = category,
            startDate = LocalDate.parse("2099-06-01"),
            endDate = LocalDate.parse("2099-06-15"),
            generatedUntil = LocalDate.parse("2099-05-31"),
        )

        recurringExpenseGenerationService.generateForRule(rule)

        expenseRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!).forEach { occurrence ->
            occurrence.defaultCurrencyAmountMinor.shouldBe(1_234)
            occurrence.defaultCurrency.shouldBe("AUD")
            occurrence.defaultCurrencyConversionSource.shouldBe(DefaultCurrencyConversionSource.SAME_CURRENCY)
        }
        expenseRepository.findById(unrelatedHistoricalExpense.id!!).get().apply {
            defaultCurrencyAmountMinor.shouldBe(null)
            defaultCurrency.shouldBe(null)
            defaultCurrencyConversionSource.shouldBe(DefaultCurrencyConversionSource.UNAVAILABLE)
        }
    }

    @Test
    fun usesRollingGenerationWindowWhenRuleHasNoEndDate() {
        val alice = saveUser("alice")
        val rule = saveRecurringRule(
            user = alice,
            startDate = LocalDate.parse("2100-06-07"),
            endDate = null,
            generatedUntil = LocalDate.parse("2100-06-06"),
        )

        val result = recurringExpenseGenerationService.generateForRule(rule)

        result.createdTransactions.shouldBe(2)
        result.generatedUntil.shouldBe(LocalDate.parse("2100-06-14"))
        generatedDates(rule).shouldContainExactly(
            LocalDate.parse("2100-06-07"),
            LocalDate.parse("2100-06-14"),
        )
    }

    private fun generatedDates(rule: RecurringTransactionRule): List<LocalDate> =
        expenseRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!)
            .map { it.recurringInstanceDate!! }

    private fun saveUser(username: String): User = userRepository.save(
        User(username = username, passwordHash = passwordHasher.hash("password"), type = UserType.USER),
    )

    private fun saveAccount(user: User): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = "Main",
            currency = "AUD",
            initialBalanceMinor = 0,
            isDefault = true,
        ),
    )

    private fun saveCategory(user: User): ExpenseCategory = expenseCategoryRepository.save(
        ExpenseCategory(userId = user.id!!, name = "General"),
    )

    private fun saveRecurringRule(
        user: User,
        account: TrackingAccount = saveAccount(user),
        category: ExpenseCategory = saveCategory(user),
        startDate: LocalDate,
        endDate: LocalDate?,
        generatedUntil: LocalDate,
        amountMinor: Long = 1234,
        notes: String? = "Rent",
    ): RecurringTransactionRule = recurringExpenseRuleRepository.save(
        RecurringTransactionRule(
            userId = user.id!!,
                transactionType = TransactionType.EXPENSE,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            startDate = startDate,
            endDate = endDate,
            recurrenceFrequency = 1,
            recurrenceInterval = RecurrenceInterval.WEEK,
            generatedUntil = generatedUntil,
            amountMinor = amountMinor,
            notes = notes,
        ),
    )

    private fun saveGeneratedExpense(
        user: User,
        account: TrackingAccount,
        category: ExpenseCategory,
        rule: RecurringTransactionRule,
        instanceDate: LocalDate,
        amountMinor: Long,
        notes: String,
        recurringLocked: Boolean,
    ): Transaction = expenseRepository.save(
        Transaction(
            userId = user.id!!,
                type = TransactionType.EXPENSE,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            date = instanceDate,
            amountMinor = amountMinor,
            notes = notes,
            recurringRuleId = rule.id,
            recurringInstanceDate = instanceDate,
            recurringLocked = recurringLocked,
        ),
    )
}
