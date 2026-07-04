package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.DateCreated
import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import java.time.Instant

@MappedEntity("account_adjustments")
data class AccountAdjustment(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    val userId: Long,
    val trackingAccountId: Long,
    val adjustmentAmountMinor: Long,
    @field:DateCreated
    val createdAt: Instant? = null,
)
