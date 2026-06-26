package io.orangebuffalo.renalo

import io.kotest.matchers.shouldBe
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import io.orangebuffalo.renalo.test.TestTimeProvider
import io.orangebuffalo.renalo.tracking.ExpenseRepository
import io.orangebuffalo.renalo.tracking.RecurringExpenseGenerationJob
import io.orangebuffalo.renalo.tracking.RecurringExpenseGenerationResult
import io.orangebuffalo.renalo.tracking.RecurringExpenseGenerationService
import io.orangebuffalo.renalo.tracking.RecurringExpenseRule
import io.orangebuffalo.renalo.tracking.RecurringExpenseRuleRepository
import io.orangebuffalo.renalo.tracking.RecurringExpenseSkipRepository
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.LocalDate

class RecurringExpenseGenerationJobTest {
    @Test
    fun processesActiveRulesThroughGenerationService() {
        val firstRule = recurringRule(1)
        val secondRule = recurringRule(2)
        val service = FakeRecurringExpenseGenerationService(
            mapOf(
                1L to RecurringExpenseGenerationResult(
                    ruleId = 1,
                    generatedUntil = LocalDate.parse("2100-06-14"),
                    createdExpenses = 2,
                    updatedExpenses = 1,
                ),
                2L to RecurringExpenseGenerationResult(
                    ruleId = 2,
                    generatedUntil = LocalDate.parse("2100-06-14"),
                    skippedOccurrences = 3,
                ),
            ),
        )

        val summary = generationJob(service).generateForRules(listOf(firstRule, secondRule))

        service.processedRuleIds.shouldBe(listOf(1L, 2L))
        summary.activeRules.shouldBe(2)
        summary.processedRules.shouldBe(2)
        summary.failedRules.shouldBe(0)
        summary.createdExpenses.shouldBe(2)
        summary.updatedExpenses.shouldBe(1)
        summary.skippedOccurrences.shouldBe(3)
    }

    @Test
    fun continuesProcessingLaterRulesWhenOneRuleFails() {
        val service = FakeRecurringExpenseGenerationService(
            results = mapOf(
                1L to RecurringExpenseGenerationResult(ruleId = 1, generatedUntil = LocalDate.parse("2100-06-14")),
                3L to RecurringExpenseGenerationResult(
                    ruleId = 3,
                    generatedUntil = LocalDate.parse("2100-06-14"),
                    createdExpenses = 4,
                ),
            ),
            failingRuleIds = setOf(2L),
        )

        val summary = generationJob(service).generateForRules(
            listOf(recurringRule(1), recurringRule(2), recurringRule(3)),
        )

        service.processedRuleIds.shouldBe(listOf(1L, 2L, 3L))
        summary.activeRules.shouldBe(3)
        summary.processedRules.shouldBe(2)
        summary.failedRules.shouldBe(1)
        summary.createdExpenses.shouldBe(4)
    }

    private fun generationJob(service: RecurringExpenseGenerationService): RecurringExpenseGenerationJob =
        RecurringExpenseGenerationJob(unusedRepository(), service)

    private fun recurringRule(id: Long): RecurringExpenseRule = RecurringExpenseRule(
        id = id,
        userId = 10,
        trackingAccountId = 20,
        categoryId = 30,
        startDate = LocalDate.parse("2099-06-14"),
        endDate = null,
        recurrenceFrequency = 1,
        recurrenceInterval = RecurrenceInterval.WEEK,
        generatedUntil = LocalDate.parse("2099-06-13"),
        amountMinor = 1234,
        notes = "Rent",
    )

    private class FakeRecurringExpenseGenerationService(
        private val results: Map<Long, RecurringExpenseGenerationResult>,
        private val failingRuleIds: Set<Long> = emptySet(),
    ) : RecurringExpenseGenerationService(
        unusedRepository(),
        unusedSkipRepository(),
        unusedExpenseRepository(),
        TestTimeProvider(),
    ) {
        val processedRuleIds = mutableListOf<Long>()

        override fun generateForRule(rule: RecurringExpenseRule): RecurringExpenseGenerationResult {
            val ruleId = rule.id!!
            processedRuleIds += ruleId
            if (ruleId in failingRuleIds) {
                error("Generation failed for rule $ruleId")
            }
            return results.getValue(ruleId)
        }
    }
}

private inline fun <reified T : Any> unusedProxy(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("Unexpected call to ${method.name}")
    } as T

private fun unusedRepository(): RecurringExpenseRuleRepository = unusedProxy()

private fun unusedSkipRepository(): RecurringExpenseSkipRepository = unusedProxy()

private fun unusedExpenseRepository(): ExpenseRepository = unusedProxy()
