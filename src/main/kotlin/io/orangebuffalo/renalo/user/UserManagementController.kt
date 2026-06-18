package io.orangebuffalo.renalo.user

import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import java.security.SecureRandom
import java.util.Base64

@Controller("/api/users")
@Secured(UserRoles.ADMIN)
class UserManagementController(
    private val userRepository: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val userActivationTokenService: UserActivationTokenService,
) {
    private val random = SecureRandom()

    @Get
    fun listUsers(
        authentication: Authentication,
        @QueryValue(defaultValue = "0") page: Int,
        @QueryValue(defaultValue = "10") size: Int,
    ): HttpResponse<*> {
        userActivationTokenService.cleanupExpiredTokens()
        if (page < 0 || size !in 1..100) {
            return HttpResponse.badRequest<Any>()
        }

        val totalElements = userRepository.countAllUsers()
        val totalPages = if (totalElements == 0L) {
            0
        } else {
            ((totalElements + size - 1) / size).toInt()
        }
        val users = userRepository.findUsersPage(
            limit = size,
            offset = page.toLong() * size,
        )

        return HttpResponse.ok(
            UsersPageResponse(
                users = users.map { it.toManagedUserResponse(authentication.name) },
                page = page,
                size = size,
                totalElements = totalElements,
                totalPages = totalPages,
            ),
        )
    }

    @Get("/{id}")
    fun getUser(id: Long, authentication: Authentication): HttpResponse<*> {
        userActivationTokenService.cleanupExpiredTokens()
        val user = userRepository.findById(id).orElse(null)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(user.toUserDetailsResponse(authentication.name, findActivationTokenFor(user)))
    }

    @Post
    fun createUser(@Body request: CreateUserRequest): HttpResponse<*> {
        userActivationTokenService.cleanupExpiredTokens()
        val username = request.username.trim()
        if (username.isBlank()) {
            return HttpResponse.badRequest<Any>()
        }
        if (userRepository.findByUsername(username) != null) {
            return HttpResponse.status<Any>(HttpStatus.CONFLICT)
                .body(CreateUserErrorResponse("USERNAME_EXISTS"))
        }

        val password = generatedPassword()
        val user = userRepository.save(
            User(
                username = username,
                passwordHash = passwordHasher.hash(password),
                type = request.type,
                active = false,
            ),
        )
        userActivationTokenService.generateTokenForUser(
            user.id ?: error("User must be persisted before activation token can be generated"),
        )

        return HttpResponse.created(
            ManagedUserResponse(
                id = user.id ?: error("User must be persisted before it can be returned"),
                username = user.username,
                type = user.type,
                currentUser = false,
                active = user.active,
            ),
        )
    }

    @Patch("/{id}")
    fun updateUser(
        id: Long,
        authentication: Authentication,
        @Body request: UpdateUserRequest,
    ): HttpResponse<*> {
        userActivationTokenService.cleanupExpiredTokens()
        val user = userRepository.findById(id).orElse(null)
            ?: return HttpResponse.notFound<Any>()

        val username = request.username.trim()
        if (username.isBlank()) {
            return HttpResponse.badRequest<Any>()
        }

        val existingUser = userRepository.findByUsername(username)
        if (existingUser != null && existingUser.id != user.id) {
            return HttpResponse.status<Any>(HttpStatus.CONFLICT)
                .body(UpdateUserErrorResponse("USERNAME_EXISTS"))
        }

        val updatedUser = userRepository.update(user.copy(username = username))

        return HttpResponse.ok(updatedUser.toManagedUserResponse(authentication.name))
    }

    @Delete("/{id}")
    fun deleteUser(id: Long, authentication: Authentication): HttpResponse<*> {
        userActivationTokenService.cleanupExpiredTokens()
        val user = userRepository.findById(id).orElse(null)
            ?: return HttpResponse.notFound<Any>()

        if (user.username == authentication.name) {
            return HttpResponse.status<Any>(HttpStatus.CONFLICT)
                .body(DeleteUserErrorResponse("CURRENT_USER"))
        }

        userActivationTokenService.deleteTokenForUser(id)
        userRepository.deleteById(id)
        return HttpResponse.noContent<Any>()
    }

    @Post("/{id}/activation-token")
    fun regenerateActivationToken(id: Long, authentication: Authentication): HttpResponse<*> {
        userActivationTokenService.cleanupExpiredTokens()
        val user = userRepository.findById(id).orElse(null)
            ?: return HttpResponse.notFound<Any>()

        if (user.active) {
            return HttpResponse.badRequest<Any>()
        }

        val activationToken = userActivationTokenService.generateTokenForUser(
            user.id ?: error("User must be persisted before activation token can be generated"),
        )

        return HttpResponse.ok(user.toUserDetailsResponse(authentication.name, activationToken))
    }

    private fun generatedPassword(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun findActivationTokenFor(user: User): UserActivationToken? {
        if (user.active) {
            return null
        }
        return userActivationTokenService.findValidTokenForUser(
            user.id ?: error("User must be persisted before activation token can be loaded"),
        )
    }
}

private fun User.toManagedUserResponse(currentUsername: String) = ManagedUserResponse(
    id = id ?: error("User must be persisted before it can be listed"),
    username = username,
    type = type,
    currentUser = username == currentUsername,
    active = active,
)

private fun User.toUserDetailsResponse(
    currentUsername: String,
    activationToken: UserActivationToken?,
) = UserDetailsResponse(
    id = id ?: error("User must be persisted before it can be returned"),
    username = username,
    type = type,
    currentUser = username == currentUsername,
    active = active,
    activationToken = activationToken?.toActivationTokenResponse(),
)

private fun UserActivationToken.toActivationTokenResponse() = ActivationTokenResponse(
    token = token,
    expiresAt = expiresAt,
)

data class CreateUserRequest(
    val username: String,
    val type: UserType,
)

data class UpdateUserRequest(
    val username: String,
)

data class UsersPageResponse(
    val users: List<ManagedUserResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

data class ManagedUserResponse(
    val id: Long,
    val username: String,
    val type: UserType,
    val currentUser: Boolean,
    val active: Boolean,
)

data class UserDetailsResponse(
    val id: Long,
    val username: String,
    val type: UserType,
    val currentUser: Boolean,
    val active: Boolean,
    val activationToken: ActivationTokenResponse?,
)

data class ActivationTokenResponse(
    val token: String,
    val expiresAt: java.time.Instant,
)

data class CreateUserErrorResponse(
    val code: String,
)

data class DeleteUserErrorResponse(
    val code: String,
)

data class UpdateUserErrorResponse(
    val code: String,
)
