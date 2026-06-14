package io.orangebuffalo.renalo.time

import jakarta.inject.Singleton
import java.time.Instant

interface TimeProvider {
    fun now(): Instant
}

@Singleton
class SystemTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
}
