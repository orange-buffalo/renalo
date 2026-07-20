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
    private val transactionDefaultCurrencyService: TransactionDefaultCurrencyService,
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
    open fun generateForRule(
        rule: RecurringTransactionRule,
        currentDate: LocalDate = timeProvider.today(),
    ): RecurringTransactionGenerationResult {
        transactionDefaultCurrencyService.lockForUser(rule.userId)
        val currentRule = rule.id?.let { recurringTransactionRuleRepository.findById(it).orElse(rule) } ?: rule
        val targetGenerationUntil = targetGenerationUntil(currentRule, currentDate)
        if (currentRule.status != RecurringTransactionRuleStatus.ACTIVE ||
            !currentRule.generatedUntil.isBefore(targetGenerationUntil)
        ) {
            return RecurringTransactionGenerationResult(ruleId = currentRule.id, generatedUntil = currentRule.generatedUntil)
        }

        var createdTransactions = 0
        var updatedTransactions = 0
        var skippedOccurrences = 0
        val changedTransactionIds = mutableListOf<Long>()
        val schedule = RecurrenceSchedule(currentRule.recurrenceFrequency, currentRule.recurrenceInterval)

        RecurrenceCalculator.occurrencesBetween(schedule, currentRule.startDate, targetGenerationUntil)
            .forEach { instanceDate ->
                if (recurringTransactionSkipRepository.findByRecurringRuleIdAndRecurringInstanceDate(currentRule.id!!, instanceDate) != null) {
                    skippedOccurrences++
                    return@forEach
                }

                val existingTransaction = transactionRepository.findByRecurringRuleIdAndRecurringInstanceDate(currentRule.id!!, instanceDate)
                if (existingTransaction == null) {
                    saveGeneratedTransaction(currentRule, instanceDate)?.let { transactionId ->
                        createdTransactions++
                        changedTransactionIds += transactionId
                    }
                } else if (!existingTransaction.recurringLocked) {
                    updateGeneratedTransactionIfNeeded(currentRule, existingTransaction, instanceDate)?.let { transactionId ->
                        updatedTransactions++
                        changedTransactionIds += transactionId
                    }
                }
            }

        recurringTransactionRuleRepository.update(
            currentRule.copy(
                generatedUntil = targetGenerationUntil,
                lastGeneratedAt = timeProvider.now(),
            ),
        )
        transactionDefaultCurrencyService.recalculateTransactions(currentRule.userId, changedTransactionIds)

        return RecurringTransactionGenerationResult(
            ruleId = currentRule.id,
            generatedUntil = targetGenerationUntil,
            createdTransactions = createdTransactions,
            updatedTransactions = updatedTransactions,
            skippedOccurrences = skippedOccurrences,
        )
    }

    private fun targetGenerationUntil(rule: RecurringTransactionRule, currentDate: LocalDate): LocalDate {
        val windowEnd = RecurrenceCalculator.generationWindowEnd(currentDate)
        return if (rule.endDate == null || rule.endDate.isAfter(windowEnd)) {
            windowEnd
        } else {
            rule.endDate
        }
    }

    private fun saveGeneratedTransaction(rule: RecurringTransactionRule, instanceDate: LocalDate): Long? =
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
        )

    private fun updateGeneratedTransactionIfNeeded(
        rule: RecurringTransactionRule,
        transaction: Transaction,
        instanceDate: LocalDate,
    ): Long? {
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
            return null
        }

        return transactionRepository.update(updatedTransaction).id
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
