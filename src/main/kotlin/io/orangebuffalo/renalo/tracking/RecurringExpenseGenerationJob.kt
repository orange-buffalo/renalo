package io.orangebuffalo.renalo.tracking

import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
open class RecurringExpenseGenerationJob(
    private val recurringExpenseRuleRepository: RecurringExpenseRuleRepository,
    private val recurringExpenseGenerationService: RecurringExpenseGenerationService,
) {
    private val logger = LoggerFactory.getLogger(RecurringExpenseGenerationJob::class.java)

    @Scheduled(fixedDelay = "1h")
    open fun generateRecurringExpenses(): RecurringExpenseGenerationJobSummary =
        generateForRules(recurringExpenseRuleRepository.findByStatus(RecurringExpenseRuleStatus.ACTIVE))

    internal fun generateForRules(rules: List<RecurringExpenseRule>): RecurringExpenseGenerationJobSummary {
        var processedRules = 0
        var failedRules = 0
        var createdExpenses = 0
        var updatedExpenses = 0
        var skippedOccurrences = 0

        rules.forEach { rule ->
            try {
                val result = recurringExpenseGenerationService.generateForRule(rule)
                processedRules++
                createdExpenses += result.createdExpenses
                updatedExpenses += result.updatedExpenses
                skippedOccurrences += result.skippedOccurrences
            } catch (ex: Exception) {
                failedRules++
                logger.error("Failed to generate recurring expenses for rule {}", rule.id, ex)
            }
        }

        return RecurringExpenseGenerationJobSummary(
            activeRules = rules.size,
            processedRules = processedRules,
            failedRules = failedRules,
            createdExpenses = createdExpenses,
            updatedExpenses = updatedExpenses,
            skippedOccurrences = skippedOccurrences,
        )
    }
}

data class RecurringExpenseGenerationJobSummary(
    val activeRules: Int,
    val processedRules: Int,
    val failedRules: Int,
    val createdExpenses: Int,
    val updatedExpenses: Int,
    val skippedOccurrences: Int,
)
