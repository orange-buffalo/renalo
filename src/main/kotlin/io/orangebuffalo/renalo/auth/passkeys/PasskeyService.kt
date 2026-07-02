package io.orangebuffalo.renalo.auth.passkeys

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.ByteArray as YubicoByteArray
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import io.micronaut.context.annotation.Value
import io.orangebuffalo.renalo.auth.AccessTokenService
import io.orangebuffalo.renalo.time.TimeProvider
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import jakarta.inject.Singleton
import java.net.URI
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

@Singleton
class PasskeyService(
    private val passkeyCredentialRepository: PasskeyCredentialRepository,
    private val passkeyChallengeRepository: PasskeyChallengeRepository,
    private val userRepository: UserRepository,
    private val credentialRepositoryAdapter: PasskeyCredentialRepositoryAdapter,
    private val accessTokenService: AccessTokenService,
    private val timeProvider: TimeProvider,
    @Value("\${renalo.public-url}")
    private val publicUrl: String,
) {
    private val objectMapper = ObjectMapper()

    private val relyingParty: RelyingParty by lazy {
        val origin = publicUrl.trimEnd('/')
        val rpId = URI(origin).host ?: throw IllegalStateException("renalo.public-url must include a host")
        RelyingParty.builder()
            .identity(
                RelyingPartyIdentity.builder()
                    .id(rpId)
                    .name("Renalo")
                    .build(),
            )
            .credentialRepository(credentialRepositoryAdapter)
            .origins(setOf(origin))
            .allowOriginPort(true)
            .allowUntrustedAttestation(true)
            .build()
    }

    fun listPasskeys(username: String): List<PasskeyResponse> {
        val user = userRepository.findByUsername(username) ?: return emptyList()
        val userId = user.id ?: return emptyList()
        return passkeyCredentialRepository.findByUserId(userId)
            .map { it.toResponse() }
    }

    fun startRegistration(username: String, device: String?): PasskeyOptionsResponse {
        val user = requireActiveUser(username)
        val userId = user.id ?: throw PasskeyOperationException("Persisted user is missing id")
        cleanupExpiredChallenges()

        val userHandle = existingUserHandle(userId) ?: generateUserHandle()
        val options = relyingParty.startRegistration(
            StartRegistrationOptions.builder()
                .user(
                    UserIdentity.builder()
                        .name(user.username)
                        .displayName(user.username)
                        .id(YubicoByteArray.fromBase64Url(userHandle))
                        .build(),
                )
                .authenticatorSelection(
                    AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.PREFERRED)
                        .build(),
                )
                .timeout(challengeTimeout.toMillis())
                .build(),
        )
        saveChallenge(
            requestId = options.challenge.base64Url,
            userId = userId,
            type = PasskeyChallengeType.REGISTRATION,
            requestJson = options.toJson(),
            device = device,
        )
        return PasskeyOptionsResponse(
            requestId = options.challenge.base64Url,
            publicKey = parsePublicKeyOptions(options.toCredentialsCreateJson()),
        )
    }

    fun finishRegistration(username: String, request: FinishPasskeyRegistrationRequest): PasskeyResponse {
        val user = requireActiveUser(username)
        val userId = user.id ?: throw PasskeyOperationException("Persisted user is missing id")
        val challenge = requireChallenge(request.requestId, PasskeyChallengeType.REGISTRATION)
        if (challenge.userId != userId) {
            throw PasskeyOperationException("Passkey registration request does not belong to the current user")
        }

        val creationOptions = PublicKeyCredentialCreationOptions.fromJson(challenge.requestJson)
        val registrationResult = relyingParty.finishRegistration(
            FinishRegistrationOptions.builder()
                .request(creationOptions)
                .response(PublicKeyCredential.parseRegistrationResponseJson(objectMapper.writeValueAsString(request.credential)))
                .build(),
        )
        val now = timeProvider.now()
        val savedCredential = passkeyCredentialRepository.save(
            PasskeyCredential(
                userId = userId,
                credentialId = registrationResult.keyId.id.base64Url,
                userHandle = creationOptions.user.id.base64Url,
                publicKeyCose = registrationResult.publicKeyCose.base64Url,
                signatureCount = registrationResult.signatureCount,
                device = normalizeDevice(challenge.device),
                transports = registrationResult.keyId.transports
                    .map { transports -> transports.joinToString(",") { it.id } }
                    .orElse(null),
                backupEligible = registrationResult.isBackupEligible,
                backedUp = registrationResult.isBackedUp,
                createdAt = now,
            ),
        )
        passkeyChallengeRepository.deleteByRequestId(challenge.requestId)
        return savedCredential.toResponse()
    }

    fun deletePasskey(username: String, passkeyId: Long) {
        val user = requireActiveUser(username)
        val userId = user.id ?: throw PasskeyOperationException("Persisted user is missing id")
        passkeyCredentialRepository.deleteByIdAndUserId(passkeyId, userId)
        if (passkeyCredentialRepository.findByUserId(userId).isEmpty() && user.passwordSignInDisabled) {
            user.passwordSignInDisabled = false
            userRepository.update(user)
        }
    }

    fun startAuthentication(): PasskeyOptionsResponse {
        cleanupExpiredChallenges()
        val assertionRequest = relyingParty.startAssertion(
            StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.PREFERRED)
                .timeout(challengeTimeout.toMillis())
                .build(),
        )
        val requestId = assertionRequest.publicKeyCredentialRequestOptions.challenge.base64Url
        saveChallenge(
            requestId = requestId,
            userId = null,
            type = PasskeyChallengeType.AUTHENTICATION,
            requestJson = assertionRequest.toJson(),
            device = null,
        )
        return PasskeyOptionsResponse(
            requestId = requestId,
            publicKey = parsePublicKeyOptions(assertionRequest.toCredentialsGetJson()),
        )
    }

    fun finishAuthentication(request: FinishPasskeyAuthenticationRequest): PasskeyAuthTokenResponse {
        val challenge = requireChallenge(request.requestId, PasskeyChallengeType.AUTHENTICATION)
        val assertionResult = relyingParty.finishAssertion(
            FinishAssertionOptions.builder()
                .request(AssertionRequest.fromJson(challenge.requestJson))
                .response(PublicKeyCredential.parseAssertionResponseJson(objectMapper.writeValueAsString(request.credential)))
                .build(),
        )
        if (!assertionResult.isSuccess) {
            throw PasskeyAuthenticationException()
        }

        val credential = passkeyCredentialRepository.findByCredentialId(assertionResult.credentialId.base64Url)
            ?: throw PasskeyAuthenticationException()
        val user = userRepository.findById(credential.userId).orElse(null)
            ?: throw PasskeyAuthenticationException()
        if (!user.active) {
            throw PasskeyAuthenticationException()
        }

        credential.signatureCount = assertionResult.signatureCount
        credential.backupEligible = assertionResult.isBackupEligible
        credential.backedUp = assertionResult.isBackedUp
        credential.lastUsedAt = timeProvider.now()
        passkeyCredentialRepository.update(credential)
        passkeyChallengeRepository.deleteByRequestId(challenge.requestId)

        return PasskeyAuthTokenResponse(accessTokenService.issueAccessToken(user.username, user.type))
    }

    private fun requireActiveUser(username: String): User {
        val user = userRepository.findByUsername(username)
            ?: throw PasskeyOperationException("Current user does not exist")
        if (!user.active) {
            throw PasskeyOperationException("Current user is inactive")
        }
        return user
    }

    private fun existingUserHandle(userId: Long): String? = passkeyCredentialRepository.findByUserId(userId)
        .firstOrNull()
        ?.userHandle

    private fun requireChallenge(requestId: String, type: PasskeyChallengeType): PasskeyChallenge {
        val challenge = passkeyChallengeRepository.findByRequestId(requestId)
            ?: throw PasskeyOperationException("Passkey request expired or was not found")
        if (challenge.type != type || !challenge.expiresAt.isAfter(timeProvider.now())) {
            passkeyChallengeRepository.deleteByRequestId(challenge.requestId)
            throw PasskeyOperationException("Passkey request expired or was not found")
        }
        return challenge
    }

    private fun saveChallenge(
        requestId: String,
        userId: Long?,
        type: PasskeyChallengeType,
        requestJson: String,
        device: String?,
    ) {
        val now = timeProvider.now()
        passkeyChallengeRepository.save(
            PasskeyChallenge(
                requestId = requestId,
                userId = userId,
                type = type,
                requestJson = requestJson,
                device = device,
                createdAt = now,
                expiresAt = now.plus(challengeTimeout),
            ),
        )
    }

    private fun cleanupExpiredChallenges() {
        passkeyChallengeRepository.deleteByExpiresAtLessThanEquals(timeProvider.now())
    }

    private fun generateUserHandle(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun normalizeDevice(device: String?): String {
        val normalized = device?.trim()?.take(120)
        return if (normalized.isNullOrBlank()) "Unknown device" else normalized
    }

    private fun parsePublicKeyOptions(optionsJson: String): Map<String, Any?> {
        val credentialOptions = objectMapper.readValue(optionsJson, object : TypeReference<Map<String, Any?>>() {})
        @Suppress("UNCHECKED_CAST")
        return credentialOptions["publicKey"] as? Map<String, Any?>
            ?: throw PasskeyOperationException("Passkey options were not generated correctly")
    }

    companion object {
        private val secureRandom = SecureRandom()
        private val challengeTimeout: Duration = Duration.ofMinutes(5)
    }
}

private fun PasskeyCredential.toResponse(): PasskeyResponse = PasskeyResponse(
    id = id ?: throw IllegalStateException("Persisted passkey is missing id"),
    device = device,
    createdAt = createdAt,
    lastUsedAt = lastUsedAt,
)

class PasskeyOperationException(message: String) : RuntimeException(message)

class PasskeyAuthenticationException : RuntimeException("Passkey authentication failed")

data class PasskeyOptionsResponse(
    val requestId: String,
    val publicKey: Map<String, Any?>,
)

data class FinishPasskeyRegistrationRequest(
    val requestId: String,
    val credential: Map<String, Any?>,
)

data class FinishPasskeyAuthenticationRequest(
    val requestId: String,
    val credential: Map<String, Any?>,
)

data class PasskeyAuthTokenResponse(
    val token: String,
)

data class PasskeyResponse(
    val id: Long,
    val device: String,
    val createdAt: java.time.Instant,
    val lastUsedAt: java.time.Instant?,
)
