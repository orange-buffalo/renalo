package io.orangebuffalo.renalo.tracking

import io.micronaut.context.annotation.Requires
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
@Requires(property = "renalo.recurring-expense-generation-job.enabled", value = "true", defaultValue = "true")
open class RecurringTransactionGenerationJob(
    private val recurringTransactionRuleRepository: RecurringTransactionRuleRepository,
    private val recurringTransactionGenerationService: RecurringTransactionGenerationService,
) {
    private val logger = LoggerFactory.getLogger(RecurringTransactionGenerationJob::class.java)

    @Scheduled(fixedDelay = "1h")
    open fun generateRecurringTransactions(): RecurringTransactionGenerationJobSummary =
        generateForRules(recurringTransactionRuleRepository.findByStatus(RecurringTransactionRuleStatus.ACTIVE))

    internal fun generateForRules(rules: List<RecurringTransactionRule>): RecurringTransactionGenerationJobSummary {
        var processedRules = 0
        var failedRules = 0
        var createdTransactions = 0
        var updatedTransactions = 0
        var skippedOccurrences = 0

        rules.forEach { rule ->
            try {
                val result = recurringTransactionGenerationService.generateForRule(rule)
                processedRules++
                createdTransactions += result.createdTransactions
                updatedTransactions += result.updatedTransactions
                skippedOccurrences += result.skippedOccurrences
            } catch (ex: Exception) {
                failedRules++
                logger.error("Failed to generate recurring transactions for rule {}", rule.id, ex)
            }
        }

        return RecurringTransactionGenerationJobSummary(
            activeRules = rules.size,
            processedRules = processedRules,
            failedRules = failedRules,
            createdTransactions = createdTransactions,
            updatedTransactions = updatedTransactions,
            skippedOccurrences = skippedOccurrences,
        )
    }
}

data class RecurringTransactionGenerationJobSummary(
    val activeRules: Int,
    val processedRules: Int,
    val failedRules: Int,
    val createdTransactions: Int,
    val updatedTransactions: Int,
    val skippedOccurrences: Int,
)
