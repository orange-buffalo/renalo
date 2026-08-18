package io.orangebuffalo.renalo.auth

import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpRequest
import io.micronaut.http.cookie.Cookie
import io.micronaut.http.cookie.SameSite
import io.orangebuffalo.renalo.time.TimeProvider
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import jakarta.inject.Singleton
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Owns the lifecycle of remember-me tokens: issuing them, validating them and expiring them.
 *
 * Expiration is enforced server side via [RememberMeToken.expiresAt]. The cookie lifetime only
 * mirrors that deadline so browsers stop sending tokens the server would reject anyway; it is
 * never the authority. Every successful use slides both deadlines, so a device that keeps
 * refreshing stays signed in while an abandoned one expires on its own.
 */
@Singleton
class RememberMeService(
    private val rememberMeTokenRepository: RememberMeTokenRepository,
    private val userRepository: UserRepository,
    private val timeProvider: TimeProvider,
    @Value("\${renalo.auth.remember-me-token-expiration-seconds}")
    private val tokenExpirationSeconds: Long,
) {
    fun readToken(request: HttpRequest<*>): String? =
        request.cookies.findCookie(cookieName).orElse(null)?.value?.takeIf { it.isNotBlank() }

    fun issueToken(userId: Long, device: String?): Cookie {
        val rawToken = generateOpaqueToken()
        val now = timeProvider.now()
        rememberMeTokenRepository.save(
            RememberMeToken(
                userId = userId,
                tokenHash = hashToken(rawToken),
                device = normalizeDevice(device),
                createdAt = now,
                lastUsedAt = now,
                expiresAt = now.plusSeconds(tokenExpirationSeconds),
            ),
        )
        return tokenCookie(rawToken, tokenExpirationSeconds)
    }

    /**
     * Authenticates the raw token from the cookie and slides its expiration when it is still usable.
     * Returns `null` when the token is unknown, expired or bound to a user who can no longer sign in;
     * tokens that can never become usable again are deleted.
     */
    fun authenticate(rawToken: String): RememberMeAuthentication? {
        val token = rememberMeTokenRepository.findByTokenHash(hashToken(rawToken))
            ?: return null

        val now = timeProvider.now()
        if (!token.expiresAt.isAfter(now)) {
            rememberMeTokenRepository.delete(token)
            return null
        }

        val user = userRepository.findById(token.userId).orElse(null)
        if (user == null) {
            rememberMeTokenRepository.delete(token)
            return null
        }
        if (!user.active) {
            return null
        }

        token.lastUsedAt = now
        token.expiresAt = now.plusSeconds(tokenExpirationSeconds)
        rememberMeTokenRepository.update(token)

        return RememberMeAuthentication(user, tokenCookie(rawToken, tokenExpirationSeconds))
    }

    fun expiredCookie(): Cookie = tokenCookie("", 0)

    fun deleteExpiredTokens(): Long = rememberMeTokenRepository.deleteByExpiresAtLessThanEquals(timeProvider.now())

    private fun tokenCookie(value: String, maxAgeSeconds: Long): Cookie = Cookie.of(cookieName, value)
        .httpOnly(true)
        .path("/")
        .sameSite(SameSite.Lax)
        .maxAge(maxAgeSeconds)

    private fun generateOpaqueToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun normalizeDevice(device: String?): String {
        val normalized = device?.trim()?.take(120)
        return if (normalized.isNullOrBlank()) "Unknown device" else normalized
    }

    companion object {
        const val cookieName = "renalo.rememberMe"
        private val secureRandom = SecureRandom()
    }
}

data class RememberMeAuthentication(
    val user: User,
    /** Carries the sliding expiration back to the browser and must be added to the response. */
    val renewedCookie: Cookie,
)
