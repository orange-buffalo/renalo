package io.orangebuffalo.renalo.auth.passkeys

import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.cookie.Cookie
import io.micronaut.http.cookie.SameSite
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.orangebuffalo.renalo.auth.RememberMeToken
import io.orangebuffalo.renalo.auth.RememberMeTokenRepository
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.time.TimeProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Controller("/api")
class PasskeyController(
    private val passkeyService: PasskeyService,
    private val rememberMeTokenRepository: RememberMeTokenRepository,
    private val timeProvider: TimeProvider,
    @Value("\${renalo.auth.remember-me-token-expiration-seconds}")
    private val rememberMeTokenExpirationSeconds: Long,
) {
    @Get("/profile/passkeys")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun listPasskeys(authentication: Authentication): List<PasskeyResponse> =
        passkeyService.listPasskeys(authentication.name)

    @Post("/profile/passkeys/registration-options")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun startRegistration(
        authentication: Authentication,
        @Body request: StartPasskeyRegistrationRequest,
    ): HttpResponse<PasskeyOptionsResponse> = handlePasskeyOperation {
        HttpResponse.ok(passkeyService.startRegistration(authentication.name, request.device))
    }

    @Post("/profile/passkeys")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun finishRegistration(
        authentication: Authentication,
        @Body request: FinishPasskeyRegistrationRequest,
    ): HttpResponse<PasskeyResponse> = handlePasskeyOperation {
        HttpResponse.ok(passkeyService.finishRegistration(authentication.name, request))
    }

    @Delete("/profile/passkeys/{id}")
    @Secured(UserRoles.USER, UserRoles.ADMIN)
    fun deletePasskey(
        authentication: Authentication,
        @PathVariable id: Long,
    ): HttpResponse<Any> = handlePasskeyOperation {
        passkeyService.deletePasskey(authentication.name, id)
        HttpResponse.noContent()
    }

    @Post("/passkeys/authentication-options")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun startAuthentication(): HttpResponse<PasskeyOptionsResponse> = handlePasskeyOperation {
        HttpResponse.ok(passkeyService.startAuthentication())
    }

    @Post("/passkeys/create-auth-token")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun finishAuthentication(
        httpRequest: HttpRequest<*>,
        @Body request: FinishPasskeyAuthenticationRequest,
    ): HttpResponse<*> {
        return try {
            val result = passkeyService.finishAuthentication(request)
            val response = HttpResponse.ok<Any>(PasskeyAuthTokenResponse(token = result.token))
            if (result.issueRefreshToken) {
                val rawToken = createRememberMeToken(result.userId, httpRequest.headers.get("User-Agent"))
                response.cookie(
                    Cookie.of(rememberMeCookieName, rawToken)
                        .httpOnly(true)
                        .path("/")
                        .sameSite(SameSite.Lax)
                        .maxAge(rememberMeTokenExpirationSeconds),
                )
            }
            response
        } catch (_: PasskeyAuthenticationException) {
            HttpResponse.unauthorized<Any>()
        } catch (_: PasskeyOperationException) {
            HttpResponse.badRequest<Any>()
        }
    }

    private fun <T : Any> handlePasskeyOperation(operation: () -> HttpResponse<T>): HttpResponse<T> {
        return try {
            operation()
        } catch (_: PasskeyOperationException) {
            HttpResponse.badRequest()
        }
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

    companion object {
        private const val rememberMeCookieName = "renalo.rememberMe"
        private val secureRandom = SecureRandom()
    }
}

data class StartPasskeyRegistrationRequest(
    val device: String? = null,
)
