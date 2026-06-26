package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.DateCreated
import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import java.time.Instant
import java.time.LocalDate

@MappedEntity("recurring_expense_skips")
data class RecurringExpenseSkip(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    val recurringRuleId: Long,
    val recurringInstanceDate: LocalDate,
    @field:DateCreated
    val createdAt: Instant? = null,
)
