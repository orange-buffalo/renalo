package io.orangebuffalo.renalo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.data.exceptions.DataAccessException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.Expense
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.ExpenseRepository
import io.orangebuffalo.renalo.tracking.RecurringExpenseRule
import io.orangebuffalo.renalo.tracking.RecurringExpenseRuleRepository
import io.orangebuffalo.renalo.tracking.RecurringExpenseRuleStatus
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
class RecurringExpensePersistenceTest : IntegrationTestSupport() {
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
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun keepsExistingExpenseShapeNonRecurringByDefault() {
        val alice = saveUser("alice")
        val account = saveAccount(alice)
        val category = saveCategory(alice)

        val expense = expenseRepository.save(
            Expense(
                userId = alice.id!!,
                trackingAccountId = account.id!!,
                categoryId = category.id!!,
                date = LocalDate.parse("2026-06-15"),
                amountMinor = 1234,
                notes = "Milk",
            ),
        )

        val savedExpense = expenseRepository.findById(expense.id!!).get()
        savedExpense.recurringRuleId.shouldBe(null)
        savedExpense.recurringInstanceDate.shouldBe(null)
        savedExpense.recurringLocked.shouldBe(false)
    }

    @Test
    fun persistsRecurringRulesAndFindsActiveRulesForUser() {
        val alice = saveUser("alice")
        val bob = saveUser("bob")
        val aliceRule = saveRecurringRule(alice)
        saveRecurringRule(bob)

        val activeRules = recurringExpenseRuleRepository.findByUserIdAndStatus(
            alice.id!!,
            RecurringExpenseRuleStatus.ACTIVE,
        )

        activeRules.map { it.id }.shouldContainExactly(aliceRule.id)
        activeRules.single().recurrenceFrequency.shouldBe(2)
        activeRules.single().recurrenceInterval.shouldBe(RecurrenceInterval.WEEK)
    }

    @Test
    fun findsExpensesAndSkipsByRecurringRuleAndInstanceDate() {
        val alice = saveUser("alice")
        val account = saveAccount(alice)
        val category = saveCategory(alice)
        val rule = saveRecurringRule(alice, account, category)
        val instanceDate = LocalDate.parse("2026-06-22")
        val expense = expenseRepository.save(
            Expense(
                userId = alice.id!!,
                trackingAccountId = account.id!!,
                categoryId = category.id!!,
                date = instanceDate,
                amountMinor = 1234,
                recurringRuleId = rule.id,
                recurringInstanceDate = instanceDate,
            ),
        )
        val skip = recurringExpenseSkipRepository.save(
            RecurringExpenseSkip(
                recurringRuleId = rule.id!!,
                recurringInstanceDate = LocalDate.parse("2026-07-06"),
            ),
        )

        expenseRepository.findByRecurringRuleIdAndRecurringInstanceDate(rule.id!!, instanceDate)?.id
            .shouldBe(expense.id)
        recurringExpenseSkipRepository.findByRecurringRuleIdAndRecurringInstanceDate(
            rule.id!!,
            LocalDate.parse("2026-07-06"),
        )?.id.shouldBe(skip.id)
        recurringExpenseSkipRepository.findByRecurringRuleId(rule.id!!).map { it.id }.shouldContainExactly(skip.id)
    }

    @Test
    fun preventsDuplicateExpensesForSameRecurringSlot() {
        val alice = saveUser("alice")
        val account = saveAccount(alice)
        val category = saveCategory(alice)
        val rule = saveRecurringRule(alice, account, category)
        val instanceDate = LocalDate.parse("2026-06-22")
        val recurringExpense = Expense(
            userId = alice.id!!,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            date = instanceDate,
            amountMinor = 1234,
            recurringRuleId = rule.id,
            recurringInstanceDate = instanceDate,
        )

        expenseRepository.save(recurringExpense)

        shouldThrow<DataAccessException> {
            expenseRepository.save(recurringExpense.copy(id = null, notes = "Duplicate"))
        }
    }

    @Test
    fun preventsDuplicateSkipsForSameRecurringSlot() {
        val alice = saveUser("alice")
        val rule = saveRecurringRule(alice)
        val instanceDate = LocalDate.parse("2026-06-22")

        recurringExpenseSkipRepository.save(
            RecurringExpenseSkip(recurringRuleId = rule.id!!, recurringInstanceDate = instanceDate),
        )

        shouldThrow<DataAccessException> {
            recurringExpenseSkipRepository.save(
                RecurringExpenseSkip(recurringRuleId = rule.id!!, recurringInstanceDate = instanceDate),
            )
        }
    }

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
    ): RecurringExpenseRule = recurringExpenseRuleRepository.save(
        RecurringExpenseRule(
            userId = user.id!!,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            startDate = LocalDate.parse("2026-06-08"),
            recurrenceFrequency = 2,
            recurrenceInterval = RecurrenceInterval.WEEK,
            generatedUntil = LocalDate.parse("2026-06-08"),
            amountMinor = 1234,
            notes = "Rent",
        ),
    )
}
