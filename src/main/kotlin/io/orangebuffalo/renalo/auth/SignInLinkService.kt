package io.orangebuffalo.renalo.auth

import io.micronaut.context.annotation.Value
import io.orangebuffalo.renalo.time.TimeProvider
import io.orangebuffalo.renalo.user.UserRepository
import jakarta.inject.Singleton
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Singleton
class SignInLinkService(
    private val userRepository: UserRepository,
    private val accessTokenService: AccessTokenService,
    private val timeProvider: TimeProvider,
    @Value("\${renalo.public-url}")
    private val publicUrl: String,
) {
    private val links = ConcurrentHashMap<String, SignInLinkRecord>()

    fun createLink(username: String): SignInLink {
        val user = userRepository.findByUsername(username)
            ?: throw IllegalArgumentException("User not found")
        if (!user.active) {
            throw IllegalArgumentException("Inactive user")
        }

        cleanupExpiredLinks()
        val token = generateToken()
        val expiresAt = timeProvider.now().plusSeconds(signInLinkTtlSeconds)
        links[token] = SignInLinkRecord(
            userId = user.id ?: throw IllegalStateException("Persisted user is missing id"),
            expiresAt = expiresAt,
        )
        return SignInLink(
            link = "${publicUrl.trimEnd('/')}/sign-in-link?token=$token",
            token = token,
            expiresAt = expiresAt,
        )
    }

    fun consumeLink(token: String): String? {
        Thread.sleep(bruteForceDelayMillis)

        if (token.isBlank()) {
            return null
        }

        cleanupExpiredLinks()
        val link = links.remove(token)
            ?: return null
        if (link.expiresAt <= timeProvider.now()) {
            return null
        }

        val user = userRepository.findById(link.userId).orElse(null)
            ?: return null
        if (!user.active) {
            return null
        }

        return accessTokenService.issueAccessToken(user.username, user.type)
    }

    private fun cleanupExpiredLinks() {
        val now = timeProvider.now()
        links.entries.removeIf { it.value.expiresAt <= now }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val signInLinkTtlSeconds = 300L
        private const val bruteForceDelayMillis = 1_000L
        private val secureRandom = SecureRandom()
    }
}

data class SignInLink(
    val link: String,
    val token: String,
    val expiresAt: Instant,
)

private data class SignInLinkRecord(
    val userId: Long,
    val expiresAt: Instant,
)
