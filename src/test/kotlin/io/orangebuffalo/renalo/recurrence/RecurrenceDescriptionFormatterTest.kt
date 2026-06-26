package io.orangebuffalo.renalo.recurrence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RecurrenceDescriptionFormatterTest {
    @Test
    fun describesSingleFrequencySchedules() {
        RecurrenceDescriptionFormatter.describe(RecurrenceSchedule(1, RecurrenceInterval.DAY))
            .shouldBe("Repeats daily")
        RecurrenceDescriptionFormatter.describe(RecurrenceSchedule(1, RecurrenceInterval.WEEK))
            .shouldBe("Repeats weekly")
        RecurrenceDescriptionFormatter.describe(RecurrenceSchedule(1, RecurrenceInterval.MONTH))
            .shouldBe("Repeats monthly")
    }

    @Test
    fun describesMultipleFrequencySchedules() {
        RecurrenceDescriptionFormatter.describe(RecurrenceSchedule(2, RecurrenceInterval.WEEK))
            .shouldBe("Repeats every 2 weeks")
        RecurrenceDescriptionFormatter.describe(RecurrenceSchedule(3, RecurrenceInterval.MONTH))
            .shouldBe("Repeats every 3 months")
    }

    @Test
    fun includesEndDateWhenPresent() {
        RecurrenceDescriptionFormatter.describe(
            schedule = RecurrenceSchedule(1, RecurrenceInterval.MONTH),
            endDate = LocalDate.parse("2020-06-12"),
        ).shouldBe("Repeats monthly until 12 Jun 2020")
    }
}
