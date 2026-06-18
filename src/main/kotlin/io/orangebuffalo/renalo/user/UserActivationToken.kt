package io.orangebuffalo.renalo.user

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import java.time.Instant

@MappedEntity("user_activation_tokens")
data class UserActivationToken(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    var userId: Long,
    var token: String,
    var expiresAt: Instant,
)
