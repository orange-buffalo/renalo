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
                      "currentUser": true,
                      "active": true
                    },
                    {
                      "id": ${alice.id},
                      "username": "alice",
                      "type": "USER",
                      "currentUser": false,
                      "active": true
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
    fun requiresAdminForCreatingUsers() {
        saveUser("alice", "password", UserType.USER)
        saveUser("admin", "password", UserType.ADMIN)

        val userToken = api().login("alice", "password")

        val body = """
            {"username":"bob","type":"USER"}
        """.trimIndent()
        api().postJson("/api/users", body, null).statusCode().shouldBe(401)
        api().postJson("/api/users", body, userToken).statusCode().shouldBe(403)
    }

    @Test
    fun requiresAdminForReadingUser() {
        val alice = saveUser("alice", "password", UserType.USER)
        saveUser("admin", "password", UserType.ADMIN)

        val userToken = api().login("alice", "password")

        api().get("/api/users/${alice.id}", null).statusCode().shouldBe(401)
        api().get("/api/users/${alice.id}", userToken).statusCode().shouldBe(403)
    }

    @Test
    fun returnsUserForAdmin() {
        val alice = saveUser("alice", "password", UserType.USER, active = false)
        saveUser("admin", "password", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        val response = api().get("/api/users/${alice.id}", adminToken)

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "id": ${alice.id},
                  "username": "alice",
                  "type": "USER",
                  "currentUser": false,
                  "active": false
                }
            """.trimIndent(),
        )
    }

    @Test
    fun createsInactiveUserForAdmin() {
        saveUser("admin", "password", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        val response = api().postJson(
            "/api/users",
            """
                {"username":" alice ","type":"USER"}
            """.trimIndent(),
            adminToken,
        )

        response.statusCode().shouldBe(201)
        val alice = userRepository.findByUsername("alice")!!
        response.body().shouldEqualJson(
            """
                {
                  "id": ${alice.id},
                  "username": "alice",
                  "type": "USER",
                  "currentUser": false,
                  "active": false
                }
            """.trimIndent(),
        )
        alice.active.shouldBe(false)
    }

    @Test
    fun rejectsDuplicateUsernameOnCreate() {
        saveUser("admin", "password", UserType.ADMIN)
        saveUser("alice", "password", UserType.USER)
        val adminToken = api().login("admin", "password")

        val response = api().postJson(
            "/api/users",
            """
                {"username":"alice","type":"USER"}
            """.trimIndent(),
            adminToken,
        )

        response.statusCode().shouldBe(409)
        response.body().shouldEqualJson(
            """
                {
                  "code": "USERNAME_EXISTS"
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
    fun requiresAdminForUpdatingUsers() {
        val alice = saveUser("alice", "password", UserType.USER)
        saveUser("admin", "password", UserType.ADMIN)

        val userToken = api().login("alice", "password")
        val body = """
            {"username":"bob"}
        """.trimIndent()

        api().patchJson("/api/users/${alice.id}", body, null).statusCode().shouldBe(401)
        api().patchJson("/api/users/${alice.id}", body, userToken).statusCode().shouldBe(403)
    }

    @Test
    fun updatesUsernameForAdminWithoutChangingTypeOrActiveState() {
        val alice = saveUser("alice", "password", UserType.USER, active = false)
        saveUser("admin", "password", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        val response = api().patchJson(
            "/api/users/${alice.id}",
            """
                {"username":" frank "}
            """.trimIndent(),
            adminToken,
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "id": ${alice.id},
                  "username": "frank",
                  "type": "USER",
                  "currentUser": false,
                  "active": false
                }
            """.trimIndent(),
        )
        val updatedUser = userRepository.findByUsername("frank")!!
        userRepository.findByUsername("alice").shouldBeNull()
        updatedUser.active.shouldBe(false)
        updatedUser.type.shouldBe(UserType.USER)
    }

    @Test
    fun rejectsDuplicateUsernameOnUpdate() {
        val alice = saveUser("alice", "password", UserType.USER)
        saveUser("bob", "password", UserType.USER)
        saveUser("admin", "password", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        val response = api().patchJson(
            "/api/users/${alice.id}",
            """
                {"username":"bob"}
            """.trimIndent(),
            adminToken,
        )

        response.statusCode().shouldBe(409)
        response.body().shouldEqualJson(
            """
                {
                  "code": "USERNAME_EXISTS"
                }
            """.trimIndent(),
        )
        userRepository.findByUsername("alice")?.username.shouldBe("alice")
    }

    @Test
    fun rejectsTypeChangeOnUpdate() {
        val alice = saveUser("alice", "password", UserType.USER, active = false)
        saveUser("admin", "password", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        val response = api().patchJson(
            "/api/users/${alice.id}",
            """
                {"username":"frank","type":"ADMIN"}
            """.trimIndent(),
            adminToken,
        )

        response.statusCode().shouldBe(400)
        val unchangedAlice = userRepository.findByUsername("alice")!!
        unchangedAlice.active.shouldBe(false)
        unchangedAlice.type.shouldBe(UserType.USER)
    }

    @Test
    fun rejectsActiveChangeOnUpdate() {
        val alice = saveUser("alice", "password", UserType.USER, active = false)
        saveUser("admin", "password", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        val response = api().patchJson(
            "/api/users/${alice.id}",
            """
                {"username":"frank","active":true}
            """.trimIndent(),
            adminToken,
        )

        response.statusCode().shouldBe(400)
        val unchangedAlice = userRepository.findByUsername("alice")!!
        unchangedAlice.active.shouldBe(false)
        unchangedAlice.username.shouldBe("alice")
    }

    @Test
    fun returnsNotFoundWhenReadingOrUpdatingMissingUser() {
        saveUser("admin", "password", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        api().get("/api/users/999999", adminToken).statusCode().shouldBe(404)
        api().patchJson("/api/users/999999", "{\"username\":\"alice\"}", adminToken).statusCode().shouldBe(404)
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

    private fun saveUser(username: String, password: String, type: UserType, active: Boolean = true): User {
        return userRepository.save(User(username = username, passwordHash = passwordHasher.hash(password), type = type, active = active))
    }
}
