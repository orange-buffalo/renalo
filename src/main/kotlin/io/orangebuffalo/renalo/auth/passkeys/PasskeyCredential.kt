package io.orangebuffalo.renalo.auth.passkeys

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import java.time.Instant

@MappedEntity("passkey_credentials")
data class PasskeyCredential(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    var userId: Long,
    var credentialId: String,
    var userHandle: String,
    var publicKeyCose: String,
    var signatureCount: Long,
    var device: String,
    var transports: String? = null,
    var backupEligible: Boolean? = null,
    var backedUp: Boolean? = null,
    var createdAt: Instant,
    var lastUsedAt: Instant? = null,
)
