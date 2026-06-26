package io.orangebuffalo.renalo.recurrence

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object RecurrenceDescriptionFormatter {
    private val endDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun describe(
        schedule: RecurrenceSchedule,
        endDate: LocalDate? = null,
    ): String {
        val description = if (schedule.frequency == 1) {
            when (schedule.interval) {
                RecurrenceInterval.DAY -> "Repeats daily"
                RecurrenceInterval.WEEK -> "Repeats weekly"
                RecurrenceInterval.MONTH -> "Repeats monthly"
            }
        } else {
            "Repeats every ${schedule.frequency} ${schedule.interval.pluralLabel()}"
        }

        return if (endDate == null) {
            description
        } else {
            "$description until ${endDateFormatter.format(endDate)}"
        }
    }

    private fun RecurrenceInterval.pluralLabel(): String = when (this) {
        RecurrenceInterval.DAY -> "days"
        RecurrenceInterval.WEEK -> "weeks"
        RecurrenceInterval.MONTH -> "months"
    }
}
