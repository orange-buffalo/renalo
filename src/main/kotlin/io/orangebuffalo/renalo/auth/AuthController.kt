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
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.security.token.jwt.validator.JsonWebTokenValidator
import io.orangebuffalo.renalo.auth.passkeys.PasskeyCredentialRepository
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import java.time.Duration

@Controller("/api")
class AuthController(
    private val userRepository: UserRepository,
    private val rememberMeService: RememberMeService,
    private val passkeyCredentialRepository: PasskeyCredentialRepository,
    private val passwordHasher: PasswordHasher,
    private val accessTokenService: AccessTokenService,
    private val signInLinkService: SignInLinkService,
    private val jwtValidator: JsonWebTokenValidator<JWT, HttpRequest<*>>,
    @Value("\${renalo.login-bruteforce-delay}")
    private val loginBruteforceDelay: Duration,
) {
    @Post("/create-auth-token")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun createAuthToken(
        httpRequest: HttpRequest<*>,
        @Body request: CreateAuthTokenRequest,
    ): HttpResponse<*> {
        Thread.sleep(loginBruteforceDelay.toMillis())
        val user = userRepository.findByUsername(request.username)
            ?: return HttpResponse.unauthorized<Any>()

        if (!user.active || !passwordHasher.verify(request.password, user.passwordHash)) {
            return HttpResponse.unauthorized<Any>()
        }
        if (user.passwordSignInDisabled) {
            return HttpResponse.status<Any>(HttpStatus.CONFLICT)
                .body(AuthErrorResponse("PASSWORD_SIGN_IN_DISABLED"))
        }

        val token = accessTokenService.issueAccessToken(user.username, user.type)

        val response = HttpResponse.ok(CreateAuthTokenResponse(token = token))
        if (request.rememberMe) {
            response.cookie(
                rememberMeService.issueToken(
                    userId = user.id ?: throw IllegalStateException("Persisted user is missing id"),
                    device = request.rememberMeDevice ?: httpRequest.headers.get("User-Agent"),
                ),
            )
        } else {
            response.cookie(rememberMeService.expiredCookie())
        }
        return response
    }

    @Post("/refresh-access-token")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun refreshAccessToken(request: HttpRequest<*>): HttpResponse<RefreshAccessTokenResponse> {
        val rememberMeToken = rememberMeService.readToken(request)
        if (rememberMeToken != null) {
            val rememberMe = rememberMeService.authenticate(rememberMeToken)
            if (rememberMe != null) {
                val token = accessTokenService.issueAccessToken(rememberMe.user.username, rememberMe.user.type)
                return HttpResponse.ok(RefreshAccessTokenResponse(token = token))
                    .cookie(rememberMe.renewedCookie)
            }
        }

        val response = HttpResponse.ok(
            RefreshAccessTokenResponse(token = refreshAccessTokenWithBearerToken(request)),
        )
        if (rememberMeToken != null) {
            response.cookie(rememberMeService.expiredCookie())
        }
        return response
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

        return accessTokenService.issueAccessToken(user.username, user.type)
    }

    @Get("/profile")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun profile(authentication: Authentication): ProfileResponse {
        val user = userRepository.findByUsername(authentication.name)
            ?: throw IllegalStateException("Authenticated user does not exist")
        return user.toProfileResponse()
    }

    @Post("/profile/disable-password-sign-in")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun disablePasswordSignIn(authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val userId = user.id ?: throw IllegalStateException("Persisted user is missing id")
        if (passkeyCredentialRepository.findByUserId(userId).isEmpty()) {
            return HttpResponse.status<Any>(HttpStatus.CONFLICT)
                .body(AuthErrorResponse("PASSWORD_SIGN_IN_REQUIRES_PASSKEY"))
        }

        user.passwordSignInDisabled = true
        userRepository.update(user)
        return HttpResponse.ok(user.toProfileResponse())
    }

    @Post("/profile/enable-password-sign-in")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun enablePasswordSignIn(authentication: Authentication): HttpResponse<ProfileResponse> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized()
        user.passwordSignInDisabled = false
        userRepository.update(user)
        return HttpResponse.ok(user.toProfileResponse())
    }

    @Post("/profile/disable-passkey-refresh-token")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun disablePasskeyRefreshToken(authentication: Authentication): HttpResponse<ProfileResponse> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized()
        user.issueRefreshTokenOnPasskeyLogin = false
        userRepository.update(user)
        return HttpResponse.ok(user.toProfileResponse())
    }

    @Post("/profile/enable-passkey-refresh-token")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun enablePasskeyRefreshToken(authentication: Authentication): HttpResponse<ProfileResponse> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized()
        user.issueRefreshTokenOnPasskeyLogin = true
        userRepository.update(user)
        return HttpResponse.ok(user.toProfileResponse())
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

    @Post("/profile/sign-in-link")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun createSignInLink(authentication: Authentication): SignInLinkResponse {
        val signInLink = signInLinkService.createLink(authentication.name)
        return SignInLinkResponse(
            link = signInLink.link,
            expiresAt = signInLink.expiresAt,
        )
    }

    @Post("/create-auth-token-with-sign-in-link")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun createAuthTokenWithSignInLink(@Body request: SignInLinkAuthRequest): HttpResponse<CreateAuthTokenResponse> {
        val token = signInLinkService.consumeLink(request.token)
            ?: return HttpResponse.unauthorized()
        return HttpResponse.ok(CreateAuthTokenResponse(token = token))
    }

    @Get("/tracking")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun tracking(): MessageResponse = MessageResponse("tracking")

    @Get("/user-management")
    @Secured(UserRoles.ADMIN)
    fun userManagement(): MessageResponse = MessageResponse("user-management")

    private fun io.orangebuffalo.renalo.user.User.toProfileResponse() = ProfileResponse(
        username = username,
        type = type,
        passwordSignInDisabled = passwordSignInDisabled,
        issueRefreshTokenOnPasskeyLogin = issueRefreshTokenOnPasskeyLogin,
    )
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

data class AuthErrorResponse(
    val code: String,
)

data class RefreshAccessTokenResponse(
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val token: String?,
)

data class ProfileResponse(
    val username: String,
    val type: UserType,
    val passwordSignInDisabled: Boolean,
    val issueRefreshTokenOnPasskeyLogin: Boolean,
)

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

data class ChangePasswordErrorResponse(
    val code: String,
)

data class SignInLinkResponse(
    val link: String,
    val expiresAt: java.time.Instant,
)

data class SignInLinkAuthRequest(
    val token: String,
)

data class MessageResponse(
    val name: String,
)

object UserRoles {
    const val USER = "USER"
    const val ADMIN = "ADMIN"
}
