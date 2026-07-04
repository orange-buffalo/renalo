package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.DateCreated
import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import io.micronaut.data.annotation.TypeDef
import io.micronaut.data.model.DataType
import java.time.Instant
import java.time.LocalDate

@MappedEntity("account_adjustments")
data class AccountAdjustment(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    val userId: Long,
    val trackingAccountId: Long,
    val adjustmentAmountMinor: Long,
    val date: LocalDate,
    @field:DateCreated
    val createdAt: Instant? = null,
    @field:TypeDef(type = DataType.JSON)
    val metadata: Map<String, String>? = null,
)
