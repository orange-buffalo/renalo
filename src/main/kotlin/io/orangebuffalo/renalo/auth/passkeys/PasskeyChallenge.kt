package io.orangebuffalo.renalo.auth.passkeys

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import java.time.Instant

@MappedEntity("passkey_challenges")
data class PasskeyChallenge(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    var requestId: String,
    var userId: Long? = null,
    var type: PasskeyChallengeType,
    var requestJson: String,
    var device: String? = null,
    var createdAt: Instant,
    var expiresAt: Instant,
)

enum class PasskeyChallengeType {
    REGISTRATION,
    AUTHENTICATION,
}
