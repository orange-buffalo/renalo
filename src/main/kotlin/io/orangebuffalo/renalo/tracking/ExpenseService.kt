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
    open fun updateExpense(userId: Long, expenseId: Long, request: SaveExpenseRequest): SaveExpenseResult {
        val existingExpense = expenseRepository.findByIdAndUserId(expenseId, userId)
            ?: return SaveExpenseResult.BadRequest

        if (existingExpense.recurringRuleId != null) {
            return updateRecurringExpense(userId, existingExpense, request)
        }

        return saveExpense(userId, existingExpense, request)?.let { SaveExpenseResult.Saved(it) }
            ?: SaveExpenseResult.BadRequest
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

    private fun updateRecurringExpense(
        userId: Long,
        existingExpense: Expense,
        request: SaveExpenseRequest,
    ): SaveExpenseResult {
        val scope = request.recurringEditScope ?: return SaveExpenseResult.BadRequest
        val rule = recurringExpenseRuleRepository.findByIdAndUserId(existingExpense.recurringRuleId!!, userId)
            ?: return SaveExpenseResult.BadRequest
        val instanceDate = existingExpense.recurringInstanceDate ?: return SaveExpenseResult.BadRequest
        if (request.amountMinor <= 0 || request.recurrence != null || request.date != instanceDate) {
            return SaveExpenseResult.BadRequest
        }
        val account = trackingAccountRepository.findByIdAndUserId(request.trackingAccountId, userId)
            ?: return SaveExpenseResult.BadRequest
        val category = expenseCategoryRepository.findByIdAndUserId(request.categoryId, userId)
            ?: return SaveExpenseResult.BadRequest
        val notes = request.notes?.trim()?.takeIf { it.isNotBlank() }

        return when (scope) {
            RecurringExpenseEditScope.THIS_OCCURRENCE_ONLY -> {
                val savedExpense = expenseRepository.update(
                    existingExpense.copy(
                        trackingAccountId = account.id!!,
                        categoryId = category.id!!,
                        date = request.date,
                        amountMinor = request.amountMinor,
                        notes = notes,
                        recurringLocked = true,
                    ),
                )
                SaveExpenseResult.Saved(ExpenseDetails(savedExpense, account, category, rule))
            }

            RecurringExpenseEditScope.THIS_AND_ALL_FOLLOWING_OCCURRENCES -> {
                val newRule = recurringExpenseRuleRepository.save(
                    rule.copy(
                        id = null,
                        trackingAccountId = account.id!!,
                        categoryId = category.id!!,
                        startDate = instanceDate,
                        generatedUntil = instanceDate.minusDays(1),
                        lastGeneratedAt = null,
                        amountMinor = request.amountMinor,
                        notes = notes,
                    ),
                )
                recurringExpenseRuleRepository.update(rule.copy(endDate = instanceDate.minusDays(1)))
                reassignFollowingExpensesToNewRule(rule.id!!, newRule, instanceDate, request.amountMinor, notes, existingExpense.id!!)
                recurringExpenseGenerationService.generateForRule(newRule)
                val savedExpense = expenseRepository.findById(existingExpense.id!!).orElseThrow()
                SaveExpenseResult.Saved(ExpenseDetails(savedExpense, account, category, newRule))
            }

            RecurringExpenseEditScope.ALL_OCCURRENCES -> {
                val updatedRule = recurringExpenseRuleRepository.update(
                    rule.copy(
                        trackingAccountId = account.id!!,
                        categoryId = category.id!!,
                        amountMinor = request.amountMinor,
                        notes = notes,
                    ),
                )
                expenseRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!)
                    .filter { !it.recurringLocked || it.id == existingExpense.id }
                    .forEach {
                        expenseRepository.update(
                            it.copy(
                                trackingAccountId = account.id!!,
                                categoryId = category.id!!,
                                amountMinor = request.amountMinor,
                                notes = notes,
                            ),
                        )
                    }
                val savedExpense = expenseRepository.findById(existingExpense.id!!).orElseThrow()
                SaveExpenseResult.Saved(ExpenseDetails(savedExpense, account, category, updatedRule))
            }
        }
    }

    private fun reassignFollowingExpensesToNewRule(
        oldRuleId: Long,
        newRule: RecurringExpenseRule,
        instanceDate: LocalDate,
        amountMinor: Long,
        notes: String?,
        selectedExpenseId: Long,
    ) {
        expenseRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(oldRuleId)
            .filter { it.recurringInstanceDate?.let { date -> date >= instanceDate } == true }
            .forEach { expense ->
                val shouldApplyEditedValues = !expense.recurringLocked || expense.id == selectedExpenseId
                expenseRepository.update(
                    expense.copy(
                        trackingAccountId = if (shouldApplyEditedValues) newRule.trackingAccountId else expense.trackingAccountId,
                        categoryId = if (shouldApplyEditedValues) newRule.categoryId else expense.categoryId,
                        amountMinor = if (shouldApplyEditedValues) amountMinor else expense.amountMinor,
                        notes = if (shouldApplyEditedValues) notes else expense.notes,
                        recurringRuleId = newRule.id,
                    ),
                )
            }
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
    val recurringEditScope: RecurringExpenseEditScope? = null,
)

data class SaveExpenseRecurrenceRequest(
    val frequency: Int,
    val interval: RecurrenceInterval,
    val endDate: LocalDate? = null,
)

enum class RecurringExpenseEditScope {
    THIS_OCCURRENCE_ONLY,
    THIS_AND_ALL_FOLLOWING_OCCURRENCES,
    ALL_OCCURRENCES,
}
