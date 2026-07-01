package io.orangebuffalo.renalo.auth.passkeys

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.orangebuffalo.renalo.auth.UserRoles

@Controller("/api")
class PasskeyController(
    private val passkeyService: PasskeyService,
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
    fun finishAuthentication(@Body request: FinishPasskeyAuthenticationRequest): HttpResponse<PasskeyAuthTokenResponse> {
        return try {
            HttpResponse.ok(passkeyService.finishAuthentication(request))
        } catch (_: PasskeyAuthenticationException) {
            HttpResponse.unauthorized()
        } catch (_: PasskeyOperationException) {
            HttpResponse.badRequest()
        }
    }

    private fun <T : Any> handlePasskeyOperation(operation: () -> HttpResponse<T>): HttpResponse<T> {
        return try {
            operation()
        } catch (_: PasskeyOperationException) {
            HttpResponse.badRequest()
        }
    }
}

data class StartPasskeyRegistrationRequest(
    val device: String? = null,
)
