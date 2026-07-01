package io.orangebuffalo.renalo.auth

import com.fasterxml.jackson.annotation.JsonInclude
import com.nimbusds.jwt.JWT
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.Post
import io.micronaut.http.cookie.Cookie
import io.micronaut.http.cookie.SameSite
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.security.token.generator.TokenGenerator
import io.micronaut.security.token.jwt.validator.JsonWebTokenValidator
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
    private val jwtValidator: JsonWebTokenValidator<JWT, HttpRequest<*>>,
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
        if (rememberMeToken != null) {
            val refreshedToken = refreshAccessTokenWithRememberMeToken(rememberMeToken)
            if (refreshedToken != null) {
                return HttpResponse.ok(RefreshAccessTokenResponse(token = refreshedToken))
            }
        }

        val response = HttpResponse.ok(
            RefreshAccessTokenResponse(token = refreshAccessTokenWithBearerToken(request)),
        )
        if (rememberMeToken != null) {
            response.cookie(expireRememberMeCookie())
        }
        return response
    }

    private fun refreshAccessTokenWithRememberMeToken(rememberMeToken: String): String? {
        val tokenRecord = rememberMeTokenRepository.findByTokenHash(hashRememberMeToken(rememberMeToken))
            ?: return null
        val user = userRepository.findById(tokenRecord.userId).orElse(null)
            ?: return null
        if (!user.active) {
            return null
        }

        tokenRecord.lastUsedAt = timeProvider.now()
        rememberMeTokenRepository.update(tokenRecord)

        return issueAccessToken(user.username, user.type)
    }

    private fun refreshAccessTokenWithBearerToken(request: HttpRequest<*>): String? {
        val bearerToken = request.headers.get("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(" ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val jwt = jwtValidator.validate(bearerToken, request).orElse(null)
            ?: return null
        val username = jwt.jwtClaimsSet.subject
            ?: return null
        val user = userRepository.findByUsername(username)
            ?: return null
        if (!user.active) {
            return null
        }

        return issueAccessToken(user.username, user.type)
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

    @Patch("/profile/password")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun changePassword(authentication: Authentication, @Body request: ChangePasswordRequest): HttpResponse<*> {
        if (request.currentPassword.isBlank() || request.newPassword.isBlank()) {
            return HttpResponse.badRequest<Any>()
        }

        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        if (!user.active || !passwordHasher.verify(request.currentPassword, user.passwordHash)) {
            return HttpResponse.status<Any>(HttpStatus.CONFLICT)
                .body(ChangePasswordErrorResponse("CURRENT_PASSWORD_INVALID"))
        }

        user.passwordHash = passwordHasher.hash(request.newPassword)
        userRepository.update(user)
        return HttpResponse.noContent<Any>()
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

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

data class ChangePasswordErrorResponse(
    val code: String,
)

data class MessageResponse(
    val name: String,
)

object UserRoles {
    const val USER = "USER"
    const val ADMIN = "ADMIN"
}
