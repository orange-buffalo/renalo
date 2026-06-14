package io.orangebuffalo.renalo.auth

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.security.token.generator.TokenGenerator
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType

@Controller("/api")
class AuthController(
    private val userRepository: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val tokenGenerator: TokenGenerator,
) {
    @Post("/create-auth-token")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun createAuthToken(@Body request: CreateAuthTokenRequest): HttpResponse<CreateAuthTokenResponse> {
        val user = userRepository.findByUsername(request.username)
            ?: return HttpResponse.unauthorized()

        if (!passwordHasher.verify(request.password, user.passwordHash)) {
            return HttpResponse.unauthorized()
        }

        val roles = listOf(user.type.name)
        val claims = mapOf(
            "sub" to user.username,
            "roles" to roles,
            "userType" to user.type.name,
        )
        val token = tokenGenerator.generateToken(claims)
            .orElseThrow { IllegalStateException("JWT token could not be generated") }

        return HttpResponse.ok(CreateAuthTokenResponse(token = token))
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
}

data class CreateAuthTokenRequest(
    val username: String,
    val password: String,
)

data class CreateAuthTokenResponse(
    val token: String,
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
