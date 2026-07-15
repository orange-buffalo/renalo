package io.orangebuffalo.renalo.tracking

/** Monetary arithmetic must fail rather than silently wrap a Long value. */
object FinancialMath {
    fun add(left: Long, right: Long): Long = Math.addExact(left, right)

    fun subtract(left: Long, right: Long): Long = Math.subtractExact(left, right)
}
