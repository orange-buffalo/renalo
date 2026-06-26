package io.orangebuffalo.renalo.recurrence

import java.time.LocalDate
import java.time.YearMonth

object RecurrenceCalculator {
    fun generationWindowEnd(today: LocalDate): LocalDate = today.plusMonths(12)

    fun occurrencesBetween(
        schedule: RecurrenceSchedule,
        startDate: LocalDate,
        endDateInclusive: LocalDate,
    ): List<LocalDate> {
        if (endDateInclusive.isBefore(startDate)) {
            return emptyList()
        }

        return generateSequence(0) { it + 1 }
            .map { occurrenceDate(schedule, startDate, it) }
            .takeWhile { !it.isAfter(endDateInclusive) }
            .toList()
    }

    private fun occurrenceDate(
        schedule: RecurrenceSchedule,
        startDate: LocalDate,
        occurrenceIndex: Int,
    ): LocalDate = when (schedule.interval) {
        RecurrenceInterval.DAY -> startDate.plusDays(occurrenceIndex.toLong() * schedule.frequency)
        RecurrenceInterval.WEEK -> startDate.plusWeeks(occurrenceIndex.toLong() * schedule.frequency)
        RecurrenceInterval.MONTH -> monthlyOccurrence(startDate, occurrenceIndex * schedule.frequency)
    }

    private fun monthlyOccurrence(startDate: LocalDate, monthsToAdd: Int): LocalDate {
        val targetMonth = YearMonth.from(startDate).plusMonths(monthsToAdd.toLong())
        val targetDay = minOf(startDate.dayOfMonth, targetMonth.lengthOfMonth())
        return targetMonth.atDay(targetDay)
    }
}
