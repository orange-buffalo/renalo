package io.orangebuffalo.renalo.time

import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset

const val CLIENT_TIME_ZONE_HEADER = "X-Time-Zone"

fun parseClientTimeZone(value: String?): ZoneId? =
    try {
        value?.let(ZoneId::of) ?: ZoneOffset.UTC
    } catch (_: DateTimeException) {
        null
    }
