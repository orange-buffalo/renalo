package io.orangebuffalo.renalo.recurrence

data class RecurrenceSchedule(
    val frequency: Int,
    val interval: RecurrenceInterval,
) {
    init {
        require(frequency > 0) { "Recurrence frequency must be positive" }
    }
}
