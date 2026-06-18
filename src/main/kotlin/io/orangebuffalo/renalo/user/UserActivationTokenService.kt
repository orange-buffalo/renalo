package io.orangebuffalo.renalo.user

import io.orangebuffalo.renalo.time.TimeProvider
import jakarta.inject.Singleton
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

@Singleton
class UserActivationTokenService(
    private val userActivationTokenRepository: UserActivationTokenRepository,
    private val timeProvider: TimeProvider,
) {
    private val random = SecureRandom()

    fun cleanupExpiredTokens() {
        userActivationTokenRepository.deleteByExpiresAtLessThanEquals(timeProvider.now())
    }

    fun findValidTokenForUser(userId: Long): UserActivationToken? {
        cleanupExpiredTokens()
        return userActivationTokenRepository.findByUserId(userId)
    }

    fun generateTokenForUser(userId: Long): UserActivationToken {
        cleanupExpiredTokens()
        userActivationTokenRepository.deleteByUserId(userId)
        return userActivationTokenRepository.save(
            UserActivationToken(
                userId = userId,
                token = generatedToken(),
                expiresAt = timeProvider.now().plus(Duration.ofHours(24)),
            ),
        )
    }

    fun deleteTokenForUser(userId: Long) {
        cleanupExpiredTokens()
        userActivationTokenRepository.deleteByUserId(userId)
    }

    private fun generatedToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
