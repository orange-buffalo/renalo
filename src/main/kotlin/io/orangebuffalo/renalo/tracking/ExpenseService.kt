package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import jakarta.inject.Singleton
import java.time.LocalDate

@Singleton
open class ExpenseService(
    private val expenseRepository: ExpenseRepository,
    private val trackingAccountRepository: TrackingAccountRepository,
    private val expenseCategoryRepository: ExpenseCategoryRepository,
    private val recurringExpenseRuleRepository: RecurringExpenseRuleRepository,
    private val recurringExpenseGenerationService: RecurringExpenseGenerationService,
) {
    fun listExpenses(userId: Long): List<ExpenseDetails> =
        expenseRepository.findByUserIdOrderByDateDesc(userId).mapNotNull { it.toDetails(userId) }

    fun findExpense(userId: Long, expenseId: Long): ExpenseDetails? =
        expenseRepository.findByIdAndUserId(expenseId, userId)?.toDetails(userId)

    @Transactional
    open fun createExpense(userId: Long, request: SaveExpenseRequest): SaveExpenseResult {
        val recurrence = request.recurrence
        return if (recurrence == null) {
            saveExpense(userId, null, request)?.let { SaveExpenseResult.Saved(it) }
                ?: SaveExpenseResult.BadRequest
        } else {
            createRecurringExpense(userId, request, recurrence)
        }
    }

    @Transactional
    open fun updateExpense(userId: Long, expenseId: Long, request: SaveExpenseRequest): ExpenseDetails? {
        val existingExpense = expenseRepository.findByIdAndUserId(expenseId, userId)
            ?: return null

        return saveExpense(userId, existingExpense, request)
    }

    @Transactional
    open fun deleteExpense(userId: Long, expenseId: Long): Boolean {
        val expense = expenseRepository.findByIdAndUserId(expenseId, userId)
            ?: return false
        expenseRepository.delete(expense)
        return true
    }

    private fun saveExpense(userId: Long, existingExpense: Expense?, request: SaveExpenseRequest): ExpenseDetails? {
        if (request.amountMinor <= 0) {
            return null
        }
        val account = trackingAccountRepository.findByIdAndUserId(request.trackingAccountId, userId)
            ?: return null
        val category = expenseCategoryRepository.findByIdAndUserId(request.categoryId, userId)
            ?: return null
        val notes = request.notes?.trim()?.takeIf { it.isNotBlank() }
        val expense = Expense(
            id = existingExpense?.id,
            userId = userId,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            date = request.date,
            amountMinor = request.amountMinor,
            notes = notes,
        )

        val savedExpense = if (existingExpense == null) {
            expenseRepository.save(expense)
        } else {
            expenseRepository.update(expense)
        }
        return ExpenseDetails(savedExpense, account, category)
    }

    private fun createRecurringExpense(
        userId: Long,
        request: SaveExpenseRequest,
        recurrence: SaveExpenseRecurrenceRequest,
    ): SaveExpenseResult {
        if (request.amountMinor <= 0 || recurrence.frequency <= 0 || recurrence.endDate?.isBefore(request.date) == true) {
            return SaveExpenseResult.BadRequest
        }
        val account = trackingAccountRepository.findByIdAndUserId(request.trackingAccountId, userId)
            ?: return SaveExpenseResult.BadRequest
        val category = expenseCategoryRepository.findByIdAndUserId(request.categoryId, userId)
            ?: return SaveExpenseResult.BadRequest
        val notes = request.notes?.trim()?.takeIf { it.isNotBlank() }
        val rule = recurringExpenseRuleRepository.save(
            RecurringExpenseRule(
                userId = userId,
                trackingAccountId = account.id!!,
                categoryId = category.id!!,
                startDate = request.date,
                endDate = recurrence.endDate,
                recurrenceFrequency = recurrence.frequency,
                recurrenceInterval = recurrence.interval,
                generatedUntil = request.date.minusDays(1),
                amountMinor = request.amountMinor,
                notes = notes,
            ),
        )

        recurringExpenseGenerationService.generateForRule(rule)
        val firstExpense = expenseRepository.findByRecurringRuleIdAndRecurringInstanceDate(rule.id!!, request.date)
            ?: error("Recurring expense generation did not create the first occurrence for rule ${rule.id}")

        return SaveExpenseResult.Saved(ExpenseDetails(firstExpense, account, category, rule))
    }

    private fun Expense.toDetails(userId: Long): ExpenseDetails? {
        val account = trackingAccountRepository.findByIdAndUserId(trackingAccountId, userId)
            ?: return null
        val category = expenseCategoryRepository.findByIdAndUserId(categoryId, userId)
            ?: return null
        val recurringRule = recurringRuleId?.let { recurringExpenseRuleRepository.findByIdAndUserId(it, userId) }
        return ExpenseDetails(this, account, category, recurringRule)
    }
}

data class ExpenseDetails(
    val expense: Expense,
    val account: TrackingAccount,
    val category: ExpenseCategory,
    val recurringRule: RecurringExpenseRule? = null,
)

sealed interface SaveExpenseResult {
    data class Saved(val expense: ExpenseDetails) : SaveExpenseResult

    data object BadRequest : SaveExpenseResult
}

data class SaveExpenseRequest(
    val trackingAccountId: Long,
    val categoryId: Long,
    val date: LocalDate,
    val amountMinor: Long,
    val notes: String? = null,
    val recurrence: SaveExpenseRecurrenceRequest? = null,
)

data class SaveExpenseRecurrenceRequest(
    val frequency: Int,
    val interval: RecurrenceInterval,
    val endDate: LocalDate? = null,
)
