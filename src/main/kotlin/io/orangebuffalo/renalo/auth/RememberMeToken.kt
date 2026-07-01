package io.orangebuffalo.renalo.auth

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import java.time.Instant

@MappedEntity("remember_me_tokens")
data class RememberMeToken(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    var userId: Long,
    var tokenHash: String,
    var device: String,
    var createdAt: Instant,
    var lastUsedAt: Instant,
)
