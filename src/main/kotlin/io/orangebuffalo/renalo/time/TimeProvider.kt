package io.orangebuffalo.renalo.time

import jakarta.inject.Singleton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

interface TimeProvider {
    fun now(): Instant

    fun today(): LocalDate = LocalDate.ofInstant(now(), ZoneOffset.UTC)

    fun today(timeZone: ZoneId): LocalDate = LocalDate.ofInstant(now(), timeZone)
}

@Singleton
class SystemTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
}
