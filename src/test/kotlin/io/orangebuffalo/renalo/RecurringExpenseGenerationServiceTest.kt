package io.orangebuffalo.renalo

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestTimeProvider
import io.orangebuffalo.renalo.tracking.Expense
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.ExpenseRepository
import io.orangebuffalo.renalo.tracking.RecurringExpenseGenerationService
import io.orangebuffalo.renalo.tracking.RecurringExpenseRule
import io.orangebuffalo.renalo.tracking.RecurringExpenseRuleRepository
import io.orangebuffalo.renalo.tracking.RecurringExpenseSkip
import io.orangebuffalo.renalo.tracking.RecurringExpenseSkipRepository
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
class RecurringExpenseGenerationServiceTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var expenseRepository: ExpenseRepository

    @Inject
    lateinit var recurringExpenseRuleRepository: RecurringExpenseRuleRepository

    @Inject
    lateinit var recurringExpenseSkipRepository: RecurringExpenseSkipRepository

    @Inject
    lateinit var recurringExpenseGenerationService: RecurringExpenseGenerationService

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

        result.createdExpenses.shouldBe(5)
        result.updatedExpenses.shouldBe(0)
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

        recurringExpenseGenerationService.generateForRule(rule).createdExpenses.shouldBe(3)
        val updatedRule = recurringExpenseRuleRepository.findById(rule.id!!).get()

        recurringExpenseGenerationService.generateForRule(updatedRule).createdExpenses.shouldBe(0)
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
            RecurringExpenseSkip(
                recurringRuleId = rule.id!!,
                recurringInstanceDate = LocalDate.parse("2099-06-15"),
            ),
        )

        val result = recurringExpenseGenerationService.generateForRule(rule)

        result.createdExpenses.shouldBe(3)
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

        result.createdExpenses.shouldBe(0)
        result.updatedExpenses.shouldBe(1)
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
    fun usesRollingGenerationWindowWhenRuleHasNoEndDate() {
        val alice = saveUser("alice")
        val rule = saveRecurringRule(
            user = alice,
            startDate = LocalDate.parse("2100-06-07"),
            endDate = null,
            generatedUntil = LocalDate.parse("2100-06-06"),
        )

        val result = recurringExpenseGenerationService.generateForRule(rule)

        result.createdExpenses.shouldBe(2)
        result.generatedUntil.shouldBe(LocalDate.parse("2100-06-14"))
        generatedDates(rule).shouldContainExactly(
            LocalDate.parse("2100-06-07"),
            LocalDate.parse("2100-06-14"),
        )
    }

    private fun generatedDates(rule: RecurringExpenseRule): List<LocalDate> =
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
    ): RecurringExpenseRule = recurringExpenseRuleRepository.save(
        RecurringExpenseRule(
            userId = user.id!!,
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
        rule: RecurringExpenseRule,
        instanceDate: LocalDate,
        amountMinor: Long,
        notes: String,
        recurringLocked: Boolean,
    ): Expense = expenseRepository.save(
        Expense(
            userId = user.id!!,
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
