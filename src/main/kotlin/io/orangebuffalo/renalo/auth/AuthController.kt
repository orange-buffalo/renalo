package io.orangebuffalo.renalo.auth

import com.nimbusds.jwt.JWT
import com.nimbusds.jwt.JWTClaimsSet
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
import io.micronaut.security.token.jwt.validator.JsonWebTokenValidator
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import io.orangebuffalo.renalo.time.TimeProvider
import java.text.ParseException

@Controller("/api")
class AuthController(
    private val userRepository: UserRepository,
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
    fun createAuthToken(@Body request: CreateAuthTokenRequest): HttpResponse<CreateAuthTokenResponse> {
        val user = userRepository.findByUsername(request.username)
            ?: return HttpResponse.unauthorized()

        if (!user.active || !passwordHasher.verify(request.password, user.passwordHash)) {
            return HttpResponse.unauthorized()
        }

        val token = issueAccessToken(user.username, user.type)

        val response = HttpResponse.ok(CreateAuthTokenResponse(token = token))
        if (request.rememberMe) {
            response.cookie(
                Cookie.of(rememberMeCookieName, issueRememberMeToken(user.username, user.type))
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

        val jwt = jwtValidator.validate(rememberMeToken, request).orElse(null)
            ?: return HttpResponse.ok(RefreshAccessTokenResponse(token = null)).cookie(expireRememberMeCookie())

        val claims = jwt.jwtClaimsSet
        if (claims.getStringClaimOrNull("tokenUse") != rememberMeTokenUse) {
            return HttpResponse.ok(RefreshAccessTokenResponse(token = null)).cookie(expireRememberMeCookie())
        }

        val username = claims.subject
            ?: return HttpResponse.ok(RefreshAccessTokenResponse(token = null)).cookie(expireRememberMeCookie())
        val user = userRepository.findByUsername(username)
            ?: return HttpResponse.ok(RefreshAccessTokenResponse(token = null)).cookie(expireRememberMeCookie())
        if (!user.active) {
            return HttpResponse.ok(RefreshAccessTokenResponse(token = null)).cookie(expireRememberMeCookie())
        }

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
        tokenUse = accessTokenUse,
    )

    private fun issueRememberMeToken(username: String, userType: UserType): String = issueToken(
        username = username,
        userType = userType,
        expiresInSeconds = rememberMeTokenExpirationSeconds,
        tokenUse = rememberMeTokenUse,
    )

    private fun issueToken(
        username: String,
        userType: UserType,
        expiresInSeconds: Long,
        tokenUse: String,
    ): String {
        val roles = listOf(userType.name)
        val claims = mapOf(
            "sub" to username,
            "roles" to roles,
            "userType" to userType.name,
            "tokenUse" to tokenUse,
            "exp" to timeProvider.now().plusSeconds(expiresInSeconds).epochSecond,
        )
        return tokenGenerator.generateToken(claims)
            .orElseThrow { IllegalStateException("JWT token could not be generated") }
    }

    private fun expireRememberMeCookie(): Cookie = Cookie.of(rememberMeCookieName, "")
        .httpOnly(true)
        .path("/")
        .sameSite(SameSite.Lax)
        .maxAge(0)

    private fun JWTClaimsSet.getStringClaimOrNull(name: String): String? = try {
        getStringClaim(name)
    } catch (_: ParseException) {
        null
    }

    companion object {
        private const val rememberMeCookieName = "renalo.rememberMe"
        private const val accessTokenUse = "access"
        private const val rememberMeTokenUse = "remember-me"
    }
}

data class CreateAuthTokenRequest(
    val username: String,
    val password: String,
    val rememberMe: Boolean = false,
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
