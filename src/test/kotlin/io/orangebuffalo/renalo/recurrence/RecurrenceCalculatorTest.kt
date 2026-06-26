package io.orangebuffalo.renalo.recurrence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RecurrenceCalculatorTest {
    @Test
    fun rejectsNonPositiveFrequencies() {
        shouldThrow<IllegalArgumentException> {
            RecurrenceSchedule(0, RecurrenceInterval.DAY)
        }
    }

    @Test
    fun calculatesDailyOccurrences() {
        val occurrences = RecurrenceCalculator.occurrencesBetween(
            schedule = RecurrenceSchedule(1, RecurrenceInterval.DAY),
            startDate = LocalDate.parse("2026-06-10"),
            endDateInclusive = LocalDate.parse("2026-06-13"),
        )

        occurrences.shouldContainExactly(
            LocalDate.parse("2026-06-10"),
            LocalDate.parse("2026-06-11"),
            LocalDate.parse("2026-06-12"),
            LocalDate.parse("2026-06-13"),
        )
    }

    @Test
    fun calculatesWeeklyOccurrences() {
        val occurrences = RecurrenceCalculator.occurrencesBetween(
            schedule = RecurrenceSchedule(1, RecurrenceInterval.WEEK),
            startDate = LocalDate.parse("2026-06-10"),
            endDateInclusive = LocalDate.parse("2026-07-01"),
        )

        occurrences.shouldContainExactly(
            LocalDate.parse("2026-06-10"),
            LocalDate.parse("2026-06-17"),
            LocalDate.parse("2026-06-24"),
            LocalDate.parse("2026-07-01"),
        )
    }

    @Test
    fun calculatesBiweeklyOccurrencesAsEveryTwoWeeks() {
        val occurrences = RecurrenceCalculator.occurrencesBetween(
            schedule = RecurrenceSchedule(2, RecurrenceInterval.WEEK),
            startDate = LocalDate.parse("2026-06-10"),
            endDateInclusive = LocalDate.parse("2026-07-22"),
        )

        occurrences.shouldContainExactly(
            LocalDate.parse("2026-06-10"),
            LocalDate.parse("2026-06-24"),
            LocalDate.parse("2026-07-08"),
            LocalDate.parse("2026-07-22"),
        )
    }

    @Test
    fun calculatesMonthlyOccurrences() {
        val occurrences = RecurrenceCalculator.occurrencesBetween(
            schedule = RecurrenceSchedule(1, RecurrenceInterval.MONTH),
            startDate = LocalDate.parse("2026-01-15"),
            endDateInclusive = LocalDate.parse("2026-04-15"),
        )

        occurrences.shouldContainExactly(
            LocalDate.parse("2026-01-15"),
            LocalDate.parse("2026-02-15"),
            LocalDate.parse("2026-03-15"),
            LocalDate.parse("2026-04-15"),
        )
    }

    @Test
    fun usesLastDayOfMonthWhenMonthlyTargetDayDoesNotExist() {
        val occurrences = RecurrenceCalculator.occurrencesBetween(
            schedule = RecurrenceSchedule(1, RecurrenceInterval.MONTH),
            startDate = LocalDate.parse("2026-01-31"),
            endDateInclusive = LocalDate.parse("2026-04-30"),
        )

        occurrences.shouldContainExactly(
            LocalDate.parse("2026-01-31"),
            LocalDate.parse("2026-02-28"),
            LocalDate.parse("2026-03-31"),
            LocalDate.parse("2026-04-30"),
        )
    }

    @Test
    fun usesLeapDayForMonthlyFallbackInLeapYears() {
        val occurrences = RecurrenceCalculator.occurrencesBetween(
            schedule = RecurrenceSchedule(1, RecurrenceInterval.MONTH),
            startDate = LocalDate.parse("2024-01-31"),
            endDateInclusive = LocalDate.parse("2024-03-31"),
        )

        occurrences.shouldContainExactly(
            LocalDate.parse("2024-01-31"),
            LocalDate.parse("2024-02-29"),
            LocalDate.parse("2024-03-31"),
        )
    }

    @Test
    fun returnsNoOccurrencesWhenEndDateIsBeforeStartDate() {
        val occurrences = RecurrenceCalculator.occurrencesBetween(
            schedule = RecurrenceSchedule(1, RecurrenceInterval.DAY),
            startDate = LocalDate.parse("2026-06-10"),
            endDateInclusive = LocalDate.parse("2026-06-09"),
        )

        occurrences.shouldBe(emptyList())
    }

    @Test
    fun calculatesGenerationWindowEnd() {
        RecurrenceCalculator.generationWindowEnd(LocalDate.parse("2026-06-25"))
            .shouldBe(LocalDate.parse("2027-06-25"))
    }
}
