package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import io.orangebuffalo.renalo.recurrence.RecurrenceCalculator
import io.orangebuffalo.renalo.recurrence.RecurrenceSchedule
import io.orangebuffalo.renalo.time.TimeProvider
import jakarta.inject.Singleton
import java.time.LocalDate

@Singleton
open class RecurringTransactionGenerationService(
    private val recurringTransactionRuleRepository: RecurringTransactionRuleRepository,
    private val recurringTransactionSkipRepository: RecurringTransactionSkipRepository,
    private val transactionRepository: TransactionRepository,
    private val timeProvider: TimeProvider,
) {
    fun generateForActiveRules(): RecurringTransactionGenerationSummary {
        val results = recurringTransactionRuleRepository.findByStatus(RecurringTransactionRuleStatus.ACTIVE)
            .map { rule -> generateForRule(rule) }
        return RecurringTransactionGenerationSummary(
            processedRules = results.size,
            createdTransactions = results.sumOf { it.createdTransactions },
            updatedTransactions = results.sumOf { it.updatedTransactions },
            skippedOccurrences = results.sumOf { it.skippedOccurrences },
        )
    }

    @Transactional
    open fun generateForRule(rule: RecurringTransactionRule): RecurringTransactionGenerationResult {
        val targetGenerationUntil = targetGenerationUntil(rule)
        if (!rule.generatedUntil.isBefore(targetGenerationUntil)) {
            return RecurringTransactionGenerationResult(ruleId = rule.id, generatedUntil = rule.generatedUntil)
        }

        var createdTransactions = 0
        var updatedTransactions = 0
        var skippedOccurrences = 0
        val schedule = RecurrenceSchedule(rule.recurrenceFrequency, rule.recurrenceInterval)

        RecurrenceCalculator.occurrencesBetween(schedule, rule.startDate, targetGenerationUntil)
            .forEach { instanceDate ->
                if (recurringTransactionSkipRepository.findByRecurringRuleIdAndRecurringInstanceDate(rule.id!!, instanceDate) != null) {
                    skippedOccurrences++
                    return@forEach
                }

                val existingTransaction = transactionRepository.findByRecurringRuleIdAndRecurringInstanceDate(rule.id!!, instanceDate)
                if (existingTransaction == null) {
                    if (saveGeneratedTransaction(rule, instanceDate)) {
                        createdTransactions++
                    }
                } else if (!existingTransaction.recurringLocked && updateGeneratedTransactionIfNeeded(rule, existingTransaction, instanceDate)) {
                    updatedTransactions++
                }
            }

        recurringTransactionRuleRepository.update(
            rule.copy(
                generatedUntil = targetGenerationUntil,
                lastGeneratedAt = timeProvider.now(),
            ),
        )

        return RecurringTransactionGenerationResult(
            ruleId = rule.id,
            generatedUntil = targetGenerationUntil,
            createdTransactions = createdTransactions,
            updatedTransactions = updatedTransactions,
            skippedOccurrences = skippedOccurrences,
        )
    }

    private fun targetGenerationUntil(rule: RecurringTransactionRule): LocalDate {
        val windowEnd = RecurrenceCalculator.generationWindowEnd(timeProvider.today())
        return if (rule.endDate == null || rule.endDate.isAfter(windowEnd)) {
            windowEnd
        } else {
            rule.endDate
        }
    }

    private fun saveGeneratedTransaction(rule: RecurringTransactionRule, instanceDate: LocalDate): Boolean =
        transactionRepository.createGeneratedTransactionIfMissing(
            userId = rule.userId,
            type = rule.transactionType,
            trackingAccountId = rule.trackingAccountId,
            categoryId = rule.categoryId,
            date = instanceDate,
            amountMinor = rule.amountMinor,
            notes = rule.notes,
            recurringRuleId = rule.id!!,
            recurringInstanceDate = instanceDate,
        ) != null

    private fun updateGeneratedTransactionIfNeeded(
        rule: RecurringTransactionRule,
        transaction: Transaction,
        instanceDate: LocalDate,
    ): Boolean {
        val updatedTransaction = transaction.copy(
            type = rule.transactionType,
            trackingAccountId = rule.trackingAccountId,
            categoryId = rule.categoryId,
            date = instanceDate,
            amountMinor = rule.amountMinor,
            notes = rule.notes,
            recurringRuleId = rule.id,
            recurringInstanceDate = instanceDate,
            recurringLocked = false,
        )
        if (updatedTransaction == transaction) {
            return false
        }

        transactionRepository.update(updatedTransaction)
        return true
    }
}

data class RecurringTransactionGenerationResult(
    val ruleId: Long?,
    val generatedUntil: LocalDate,
    val createdTransactions: Int = 0,
    val updatedTransactions: Int = 0,
    val skippedOccurrences: Int = 0,
)

data class RecurringTransactionGenerationSummary(
    val processedRules: Int,
    val createdTransactions: Int,
    val updatedTransactions: Int,
    val skippedOccurrences: Int,
)
