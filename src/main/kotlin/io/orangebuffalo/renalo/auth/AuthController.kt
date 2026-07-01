package io.orangebuffalo.renalo.auth

import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.cookie.Cookie
import io.micronaut.http.cookie.SameSite
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.security.token.generator.TokenGenerator
import io.orangebuffalo.renalo.time.TimeProvider
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Controller("/api")
class AuthController(
    private val userRepository: UserRepository,
    private val rememberMeTokenRepository: RememberMeTokenRepository,
    private val passwordHasher: PasswordHasher,
    private val tokenGenerator: TokenGenerator,
    private val timeProvider: TimeProvider,
    @Value("\${renalo.auth.access-token-expiration-seconds}")
    private val accessTokenExpirationSeconds: Long,
    @Value("\${renalo.auth.remember-me-token-expiration-seconds}")
    private val rememberMeTokenExpirationSeconds: Long,
) {
    @Post("/create-auth-token")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun createAuthToken(
        httpRequest: HttpRequest<*>,
        @Body request: CreateAuthTokenRequest,
    ): HttpResponse<CreateAuthTokenResponse> {
        val user = userRepository.findByUsername(request.username)
            ?: return HttpResponse.unauthorized()

        if (!user.active || !passwordHasher.verify(request.password, user.passwordHash)) {
            return HttpResponse.unauthorized()
        }

        val token = issueAccessToken(user.username, user.type)

        val response = HttpResponse.ok(CreateAuthTokenResponse(token = token))
        if (request.rememberMe) {
            val rememberMeToken = createRememberMeToken(
                userId = user.id ?: throw IllegalStateException("Persisted user is missing id"),
                device = request.rememberMeDevice ?: httpRequest.headers.get("User-Agent"),
            )
            response.cookie(
                Cookie.of(rememberMeCookieName, rememberMeToken)
                    .httpOnly(true)
                    .path("/")
                    .sameSite(SameSite.Lax)
                    .maxAge(rememberMeTokenExpirationSeconds),
            )
        } else {
            response.cookie(expireRememberMeCookie())
        }
        return response
    }

    @Post("/refresh-access-token")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun refreshAccessToken(request: HttpRequest<*>): HttpResponse<RefreshAccessTokenResponse> {
        val rememberMeToken = request.cookies.findCookie(rememberMeCookieName).orElse(null)?.value
            ?: return HttpResponse.ok(RefreshAccessTokenResponse(token = null))

        val tokenRecord = rememberMeTokenRepository.findByTokenHash(hashRememberMeToken(rememberMeToken))
            ?: return HttpResponse.ok(RefreshAccessTokenResponse(token = null)).cookie(expireRememberMeCookie())
        val user = userRepository.findById(tokenRecord.userId).orElse(null)
            ?: return HttpResponse.ok(RefreshAccessTokenResponse(token = null)).cookie(expireRememberMeCookie())
        if (!user.active) {
            return HttpResponse.ok(RefreshAccessTokenResponse(token = null)).cookie(expireRememberMeCookie())
        }

        tokenRecord.lastUsedAt = timeProvider.now()
        rememberMeTokenRepository.update(tokenRecord)

        return HttpResponse.ok(RefreshAccessTokenResponse(token = issueAccessToken(user.username, user.type)))
    }

    @Get("/profile")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun profile(authentication: Authentication): ProfileResponse {
        return ProfileResponse(
            username = authentication.name,
            type = authentication.roles.firstNotNullOfOrNull { role ->
                UserType.entries.find { it.name == role }
            } ?: UserType.USER,
        )
    }

    @Get("/tracking")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun tracking(): MessageResponse = MessageResponse("tracking")

    @Get("/user-management")
    @Secured(UserRoles.ADMIN)
    fun userManagement(): MessageResponse = MessageResponse("user-management")

    private fun issueAccessToken(username: String, userType: UserType): String = issueToken(
        username = username,
        userType = userType,
        expiresInSeconds = accessTokenExpirationSeconds,
    )

    private fun issueToken(
        username: String,
        userType: UserType,
        expiresInSeconds: Long,
    ): String {
        val roles = listOf(userType.name)
        val claims = mapOf(
            "sub" to username,
            "roles" to roles,
            "userType" to userType.name,
            "exp" to timeProvider.now().plusSeconds(expiresInSeconds).epochSecond,
        )
        return tokenGenerator.generateToken(claims)
            .orElseThrow { IllegalStateException("JWT token could not be generated") }
    }

    private fun createRememberMeToken(userId: Long, device: String?): String {
        val rawToken = generateOpaqueRememberMeToken()
        val now = timeProvider.now()
        rememberMeTokenRepository.save(
            RememberMeToken(
                userId = userId,
                tokenHash = hashRememberMeToken(rawToken),
                device = normalizeDevice(device),
                createdAt = now,
                lastUsedAt = now,
            ),
        )
        return rawToken
    }

    private fun generateOpaqueRememberMeToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashRememberMeToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun normalizeDevice(device: String?): String {
        val normalized = device?.trim()?.take(120)
        return if (normalized.isNullOrBlank()) "Unknown device" else normalized
    }

    private fun expireRememberMeCookie(): Cookie = Cookie.of(rememberMeCookieName, "")
        .httpOnly(true)
        .path("/")
        .sameSite(SameSite.Lax)
        .maxAge(0)

    companion object {
        private const val rememberMeCookieName = "renalo.rememberMe"
        private val secureRandom = SecureRandom()
    }
}

data class CreateAuthTokenRequest(
    val username: String,
    val password: String,
    val rememberMe: Boolean = false,
    val rememberMeDevice: String? = null,
)

data class CreateAuthTokenResponse(
    val token: String,
)

data class RefreshAccessTokenResponse(
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val token: String?,
)

data class ProfileResponse(
    val username: String,
    val type: UserType,
)

data class MessageResponse(
    val name: String,
)

object UserRoles {
    const val USER = "USER"
    const val ADMIN = "ADMIN"
}
