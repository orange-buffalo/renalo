package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestTimeProvider
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserActivationToken
import io.orangebuffalo.renalo.user.UserActivationTokenRepository
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import java.time.Instant
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class UserManagementApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var userActivationTokenRepository: UserActivationTokenRepository

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
        val activationToken = userActivationTokenRepository.findByUserId(alice.id!!).shouldNotBeNull()
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
        activationToken.expiresAt.shouldBe(Instant.parse("2099-06-15T08:00:00Z"))

        val userResponse = api().get("/api/users/${alice.id}", adminToken)
        userResponse.statusCode().shouldBe(200)
        userResponse.body().shouldEqualJson(
            """
                {
                  "id": ${alice.id},
                  "username": "alice",
                  "type": "USER",
                  "currentUser": false,
                  "active": false,
                  "activationToken": {
                    "token": "${activationToken.token}",
                    "expiresAt": "2099-06-15T08:00:00Z"
                  }
                }
            """.trimIndent(),
        )
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
    fun ignoresTypeChangeOnUpdate() {
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
        val updatedAlice = userRepository.findByUsername("frank")!!
        updatedAlice.active.shouldBe(false)
        updatedAlice.type.shouldBe(UserType.USER)
    }

    @Test
    fun ignoresActiveChangeOnUpdate() {
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
        val updatedAlice = userRepository.findByUsername("frank")!!
        updatedAlice.active.shouldBe(false)
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
        userActivationTokenRepository.save(
            UserActivationToken(
                userId = alice.id!!,
                token = "activation-token",
                expiresAt = Instant.parse("2099-06-15T08:00:00Z"),
            ),
        )

        val adminToken = api().login("admin", "password")

        val response = api().delete("/api/users/${alice.id}", adminToken)

        response.statusCode().shouldBe(204)
        userRepository.findByUsername("alice").shouldBeNull()
        userActivationTokenRepository.findByUserId(alice.id!!).shouldBeNull()
    }

    @Test
    fun requiresAdminForRegeneratingActivationTokens() {
        val alice = saveUser("alice", "password", UserType.USER, active = false)
        saveUser("bob", "password", UserType.USER)
        saveUser("admin", "password", UserType.ADMIN)

        val userToken = api().login("bob", "password")

        api().post("/api/users/${alice.id}/activation-token", null).statusCode().shouldBe(401)
        api().post("/api/users/${alice.id}/activation-token", userToken).statusCode().shouldBe(403)
    }

    @Test
    fun regeneratesActivationTokenForInactiveUser() {
        val alice = saveUser("alice", "password", UserType.USER, active = false)
        saveUser("admin", "password", UserType.ADMIN)
        userActivationTokenRepository.save(
            UserActivationToken(
                userId = alice.id!!,
                token = "old-token",
                expiresAt = Instant.parse("2099-06-15T08:00:00Z"),
            ),
        )
        val adminToken = api().login("admin", "password")

        val response = api().post("/api/users/${alice.id}/activation-token", adminToken)

        response.statusCode().shouldBe(200)
        val activationToken = userActivationTokenRepository.findByUserId(alice.id!!).shouldNotBeNull()
        (activationToken.token != "old-token").shouldBe(true)
        response.body().shouldEqualJson(
            """
                {
                  "id": ${alice.id},
                  "username": "alice",
                  "type": "USER",
                  "currentUser": false,
                  "active": false,
                  "activationToken": {
                    "token": "${activationToken.token}",
                    "expiresAt": "2099-06-15T08:00:00Z"
                  }
                }
            """.trimIndent(),
        )
    }

    @Test
    fun preventsRegeneratingActivationTokenForActiveUser() {
        val alice = saveUser("alice", "password", UserType.USER, active = true)
        saveUser("admin", "password", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        api().post("/api/users/${alice.id}/activation-token", adminToken).statusCode().shouldBe(400)
    }

    @Test
    fun cleansUpExpiredActivationTokensWhenReadingUser() {
        val alice = saveUser("alice", "password", UserType.USER, active = false)
        saveUser("admin", "password", UserType.ADMIN)
        userActivationTokenRepository.save(
            UserActivationToken(
                userId = alice.id!!,
                token = "expired-token",
                expiresAt = TestTimeProvider.DEFAULT_TIME.minusSeconds(1),
            ),
        )
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
        userActivationTokenRepository.findByUserId(alice.id!!).shouldBeNull()
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
