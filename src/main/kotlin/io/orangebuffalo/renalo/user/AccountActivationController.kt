package io.orangebuffalo.renalo.user

import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import kotlinx.coroutines.delay
import java.time.Duration

@Controller("/api/account-activation")
@Secured(SecurityRule.IS_ANONYMOUS)
class AccountActivationController(
    private val userRepository: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val userActivationTokenService: UserActivationTokenService,
    @Value("\${renalo.activation-token.delay}")
    private val activationTokenDelay: Duration,
) {
    @Get
    suspend fun getActivationStatus(@QueryValue token: String): HttpResponse<*> {
        delay(activationTokenDelay.toMillis())
        val activationToken = userActivationTokenService.findValidToken(token)
            ?: return tokenNotFound()
        val user = userRepository.findById(activationToken.userId).orElse(null)
            ?: return tokenNotFound()

        if (user.active) {
            userActivationTokenService.deleteToken(token)
            return tokenNotFound()
        }

        return HttpResponse.ok(AccountActivationStatusResponse(username = user.username))
    }

    @Post
    suspend fun activateAccount(
        @QueryValue token: String,
        @Body request: ActivateAccountRequest,
    ): HttpResponse<*> {
        delay(activationTokenDelay.toMillis())
        if (request.password.isBlank() || request.password != request.passwordConfirmation) {
            return HttpResponse.badRequest<Any>()
        }

        val activationToken = userActivationTokenService.findValidToken(token)
            ?: return tokenNotFound()
        val user = userRepository.findById(activationToken.userId).orElse(null)
            ?: return tokenNotFound()

        if (user.active) {
            userActivationTokenService.deleteToken(token)
            return tokenNotFound()
        }

        userRepository.update(
            user.copy(
                passwordHash = passwordHasher.hash(request.password),
                active = true,
            ),
        )
        userActivationTokenService.deleteToken(token)
        return HttpResponse.noContent<Any>()
    }

    private fun tokenNotFound(): HttpResponse<AccountActivationErrorResponse> =
        HttpResponse.status<AccountActivationErrorResponse>(HttpStatus.NOT_FOUND)
            .body(AccountActivationErrorResponse("TOKEN_NOT_FOUND"))
}

data class AccountActivationStatusResponse(
    val username: String,
)

data class ActivateAccountRequest(
    val password: String,
    val passwordConfirmation: String,
)

data class AccountActivationErrorResponse(
    val code: String,
)
