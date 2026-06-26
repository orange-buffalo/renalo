package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import io.orangebuffalo.renalo.recurrence.RecurrenceCalculator
import io.orangebuffalo.renalo.recurrence.RecurrenceSchedule
import io.orangebuffalo.renalo.time.TimeProvider
import jakarta.inject.Singleton
import java.time.LocalDate
import java.time.ZoneOffset

@Singleton
open class RecurringExpenseGenerationService(
    private val recurringExpenseRuleRepository: RecurringExpenseRuleRepository,
    private val recurringExpenseSkipRepository: RecurringExpenseSkipRepository,
    private val expenseRepository: ExpenseRepository,
    private val timeProvider: TimeProvider,
) {
    fun generateForActiveRules(): RecurringExpenseGenerationSummary {
        val results = recurringExpenseRuleRepository.findByStatus(RecurringExpenseRuleStatus.ACTIVE)
            .map { rule -> generateForRule(rule) }
        return RecurringExpenseGenerationSummary(
            processedRules = results.size,
            createdExpenses = results.sumOf { it.createdExpenses },
            updatedExpenses = results.sumOf { it.updatedExpenses },
            skippedOccurrences = results.sumOf { it.skippedOccurrences },
        )
    }

    @Transactional
    open fun generateForRule(rule: RecurringExpenseRule): RecurringExpenseGenerationResult {
        val targetGenerationUntil = targetGenerationUntil(rule)
        if (!rule.generatedUntil.isBefore(targetGenerationUntil)) {
            return RecurringExpenseGenerationResult(ruleId = rule.id, generatedUntil = rule.generatedUntil)
        }

        var createdExpenses = 0
        var updatedExpenses = 0
        var skippedOccurrences = 0
        val schedule = RecurrenceSchedule(rule.recurrenceFrequency, rule.recurrenceInterval)

        RecurrenceCalculator.occurrencesBetween(schedule, rule.startDate, targetGenerationUntil)
            .forEach { instanceDate ->
                if (recurringExpenseSkipRepository.findByRecurringRuleIdAndRecurringInstanceDate(rule.id!!, instanceDate) != null) {
                    skippedOccurrences++
                    return@forEach
                }

                val existingExpense = expenseRepository.findByRecurringRuleIdAndRecurringInstanceDate(rule.id!!, instanceDate)
                if (existingExpense == null) {
                    if (saveGeneratedExpense(rule, instanceDate)) {
                        createdExpenses++
                    }
                } else if (!existingExpense.recurringLocked && updateGeneratedExpenseIfNeeded(rule, existingExpense, instanceDate)) {
                    updatedExpenses++
                }
            }

        recurringExpenseRuleRepository.update(
            rule.copy(
                generatedUntil = targetGenerationUntil,
                lastGeneratedAt = timeProvider.now(),
            ),
        )

        return RecurringExpenseGenerationResult(
            ruleId = rule.id,
            generatedUntil = targetGenerationUntil,
            createdExpenses = createdExpenses,
            updatedExpenses = updatedExpenses,
            skippedOccurrences = skippedOccurrences,
        )
    }

    private fun targetGenerationUntil(rule: RecurringExpenseRule): LocalDate {
        val windowEnd = RecurrenceCalculator.generationWindowEnd(LocalDate.ofInstant(timeProvider.now(), ZoneOffset.UTC))
        return if (rule.endDate == null || rule.endDate.isAfter(windowEnd)) {
            windowEnd
        } else {
            rule.endDate
        }
    }

    private fun saveGeneratedExpense(rule: RecurringExpenseRule, instanceDate: LocalDate): Boolean =
        expenseRepository.createGeneratedExpenseIfMissing(
            userId = rule.userId,
            trackingAccountId = rule.trackingAccountId,
            categoryId = rule.categoryId,
            date = instanceDate,
            amountMinor = rule.amountMinor,
            notes = rule.notes,
            recurringRuleId = rule.id!!,
            recurringInstanceDate = instanceDate,
        ) != null

    private fun updateGeneratedExpenseIfNeeded(
        rule: RecurringExpenseRule,
        expense: Expense,
        instanceDate: LocalDate,
    ): Boolean {
        val updatedExpense = expense.copy(
            trackingAccountId = rule.trackingAccountId,
            categoryId = rule.categoryId,
            date = instanceDate,
            amountMinor = rule.amountMinor,
            notes = rule.notes,
            recurringRuleId = rule.id,
            recurringInstanceDate = instanceDate,
            recurringLocked = false,
        )
        if (updatedExpense == expense) {
            return false
        }

        expenseRepository.update(updatedExpense)
        return true
    }
}

data class RecurringExpenseGenerationResult(
    val ruleId: Long?,
    val generatedUntil: LocalDate,
    val createdExpenses: Int = 0,
    val updatedExpenses: Int = 0,
    val skippedOccurrences: Int = 0,
)

data class RecurringExpenseGenerationSummary(
    val processedRules: Int,
    val createdExpenses: Int,
    val updatedExpenses: Int,
    val skippedOccurrences: Int,
)
