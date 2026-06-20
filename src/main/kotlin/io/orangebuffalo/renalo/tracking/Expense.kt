package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import java.time.OffsetDateTime

@MappedEntity("expenses")
data class Expense(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    val userId: Long,
    val trackingAccountId: Long,
    val categoryId: Long,
    val dateTime: OffsetDateTime,
    val amountMinor: Long,
    val notes: String? = null,
)
