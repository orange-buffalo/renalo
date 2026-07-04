package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity

@MappedEntity("account_adjustments")
data class AccountAdjustment(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    val userId: Long,
    val trackingAccountId: Long,
    val adjustmentAmountMinor: Long,
)
