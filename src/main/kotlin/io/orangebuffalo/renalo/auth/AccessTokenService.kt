package io.orangebuffalo.renalo.auth

import io.micronaut.context.annotation.Value
import io.micronaut.security.token.generator.TokenGenerator
import io.orangebuffalo.renalo.time.TimeProvider
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Singleton

@Singleton
class AccessTokenService(
    private val tokenGenerator: TokenGenerator,
    private val timeProvider: TimeProvider,
    @Value("\${renalo.auth.access-token-expiration-seconds}")
    private val accessTokenExpirationSeconds: Long,
) {
    fun issueAccessToken(username: String, userType: UserType): String {
        val roles = listOf(userType.name)
        val claims = mapOf(
            "sub" to username,
            "roles" to roles,
            "userType" to userType.name,
            "exp" to timeProvider.now().plusSeconds(accessTokenExpirationSeconds).epochSecond,
        )
        return tokenGenerator.generateToken(claims)
            .orElseThrow { IllegalStateException("JWT token could not be generated") }
    }
}
