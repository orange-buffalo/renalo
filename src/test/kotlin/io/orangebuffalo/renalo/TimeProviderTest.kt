package io.orangebuffalo.renalo

import io.kotest.matchers.shouldBe
import io.orangebuffalo.renalo.time.TimeProvider
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TimeProviderTest {
    @Test
    fun derivesTodayUsingTheRequestedTimezone() {
        val timeProvider = object : TimeProvider {
            override fun now(): Instant = Instant.parse("2099-06-14T23:59:59.999Z")
        }

        timeProvider.today().shouldBe(LocalDate.parse("2099-06-14"))
        timeProvider.today(ZoneId.of("Pacific/Auckland")).shouldBe(LocalDate.parse("2099-06-15"))
        timeProvider.today(ZoneId.of("America/Los_Angeles")).shouldBe(LocalDate.parse("2099-06-14"))
    }
}
