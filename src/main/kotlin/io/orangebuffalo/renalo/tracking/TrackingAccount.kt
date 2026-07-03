package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity

@MappedEntity("tracking_accounts")
data class TrackingAccount(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    val userId: Long,
    val name: String,
    val currency: String,
    val initialBalanceMinor: Long,
    val isDefault: Boolean,
    val archived: Boolean = false,
)
