package io.orangebuffalo.renalo.user

import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
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
) {
    private val random = SecureRandom()

    @Get
    fun listUsers(
        authentication: Authentication,
        @QueryValue(defaultValue = "0") page: Int,
        @QueryValue(defaultValue = "10") size: Int,
    ): HttpResponse<*> {
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

    @Post
    fun createUser(@Body request: CreateUserRequest): HttpResponse<*> {
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

    @Delete("/{id}")
    fun deleteUser(id: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findById(id).orElse(null)
            ?: return HttpResponse.notFound<Any>()

        if (user.username == authentication.name) {
            return HttpResponse.status<Any>(HttpStatus.CONFLICT)
                .body(DeleteUserErrorResponse("CURRENT_USER"))
        }

        userRepository.deleteById(id)
        return HttpResponse.noContent<Any>()
    }

    private fun generatedPassword(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

private fun User.toManagedUserResponse(currentUsername: String) = ManagedUserResponse(
    id = id ?: error("User must be persisted before it can be listed"),
    username = username,
    type = type,
    currentUser = username == currentUsername,
    active = active,
)

data class CreateUserRequest(
    val username: String,
    val type: UserType,
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

data class CreateUserErrorResponse(
    val code: String,
)

data class DeleteUserErrorResponse(
    val code: String,
)
