package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.DateCreated
import io.micronaut.data.annotation.DateUpdated
import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import java.time.Instant
import java.time.LocalDate

@MappedEntity("recurring_expense_rules")
data class RecurringExpenseRule(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    val userId: Long,
    val trackingAccountId: Long,
    val categoryId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val recurrenceFrequency: Int,
    val recurrenceInterval: RecurrenceInterval,
    val recurrenceRule: String? = null,
    val status: RecurringExpenseRuleStatus = RecurringExpenseRuleStatus.ACTIVE,
    val generatedUntil: LocalDate,
    val lastGeneratedAt: Instant? = null,
    val amountMinor: Long,
    val notes: String? = null,
    @field:DateCreated
    val createdAt: Instant? = null,
    @field:DateUpdated
    val updatedAt: Instant? = null,
)
