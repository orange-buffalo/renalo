package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class UserManagementApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun requiresAdminForListingUsers() {
        saveUser("alice", "password", UserType.USER)
        saveUser("admin", "password", UserType.ADMIN)

        val userToken = api().login("alice", "password")

        api().get("/api/users", null).statusCode().shouldBe(401)
        api().get("/api/users", userToken).statusCode().shouldBe(403)
    }

    @Test
    fun listsUsersForAdminWithPagination() {
        saveUser("bob", "password", UserType.USER)
        val admin = saveUser("admin", "password", UserType.ADMIN)
        val alice = saveUser("alice", "password", UserType.USER)

        val adminToken = api().login("admin", "password")

        val response = api().get("/api/users?page=0&size=2", adminToken)

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "users": [
                    {
                      "id": ${admin.id},
                      "username": "admin",
                      "type": "ADMIN",
                      "currentUser": true
                    },
                    {
                      "id": ${alice.id},
                      "username": "alice",
                      "type": "USER",
                      "currentUser": false
                    }
                  ],
                  "page": 0,
                  "size": 2,
                  "totalElements": 3,
                  "totalPages": 2
                }
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsInvalidPagination() {
        saveUser("admin", "password", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        api().get("/api/users?page=-1&size=10", adminToken).statusCode().shouldBe(400)
        api().get("/api/users?page=0&size=0", adminToken).statusCode().shouldBe(400)
        api().get("/api/users?page=0&size=101", adminToken).statusCode().shouldBe(400)
    }

    @Test
    fun requiresAdminForDeletingUsers() {
        val alice = saveUser("alice", "password", UserType.USER)
        saveUser("admin", "password", UserType.ADMIN)

        val userToken = api().login("alice", "password")

        api().delete("/api/users/${alice.id}", null).statusCode().shouldBe(401)
        api().delete("/api/users/${alice.id}", userToken).statusCode().shouldBe(403)
    }

    @Test
    fun deletesAnotherUserForAdmin() {
        val alice = saveUser("alice", "password", UserType.USER)
        saveUser("admin", "password", UserType.ADMIN)

        val adminToken = api().login("admin", "password")

        val response = api().delete("/api/users/${alice.id}", adminToken)

        response.statusCode().shouldBe(204)
        userRepository.findByUsername("alice").shouldBeNull()
    }

    @Test
    fun preventsAdminFromDeletingCurrentUser() {
        val admin = saveUser("admin", "password", UserType.ADMIN)

        val adminToken = api().login("admin", "password")

        val response = api().delete("/api/users/${admin.id}", adminToken)

        response.statusCode().shouldBe(409)
        response.body().shouldEqualJson(
            """
                {
                  "code": "CURRENT_USER"
                }
            """.trimIndent(),
        )
        userRepository.findByUsername("admin")?.username.shouldBe("admin")
    }

    @Test
    fun returnsNotFoundWhenDeletingMissingUser() {
        saveUser("admin", "password", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        api().delete("/api/users/999999", adminToken).statusCode().shouldBe(404)
    }

    private fun saveUser(username: String, password: String, type: UserType): User {
        return userRepository.save(User(null, username, passwordHasher.hash(password), type))
    }
}
