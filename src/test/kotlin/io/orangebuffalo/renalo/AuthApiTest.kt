package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
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
import java.nio.charset.StandardCharsets
import java.util.Base64

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class AuthApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun rejectsInvalidCredentials() {
        saveUser("alice", "correct-password", UserType.USER)

        val response = api().postJson(
            "/api/create-auth-token",
            """
                {"username":"alice","password":"wrong-password"}
            """.trimIndent(),
            null,
        )

        response.statusCode().shouldBe(401)
    }

    @Test
    fun rejectsInactiveUsersAsInvalidCredentials() {
        userRepository.save(
            User(
                username = "alice",
                passwordHash = passwordHasher.hash("correct-password"),
                type = UserType.USER,
                active = false,
            ),
        )

        val response = api().postJson(
            "/api/create-auth-token",
            """
                {"username":"alice","password":"correct-password"}
            """.trimIndent(),
            null,
        )

        response.statusCode().shouldBe(401)
    }

    @Test
    fun issuesTokenAndReturnsProfile() {
        saveUser("alice", "correct-password", UserType.USER)

        val token = api().login("alice", "correct-password")
        val profileResponse = api().get("/api/profile", token)

        profileResponse.statusCode().shouldBe(200)
        profileResponse.body().shouldEqualJson(
            """
                {
                  "username": "alice",
                  "type": "USER"
                }
            """.trimIndent(),
        )
    }

    @Test
    fun requiresTokenForProfile() {
        val response = api().get("/api/profile", null)

        response.statusCode().shouldBe(401)
    }

    @Test
    fun enforcesRoleChecks() {
        saveUser("alice", "user-password", UserType.USER)
        saveUser("admin", "admin-password", UserType.ADMIN)

        val userToken = api().login("alice", "user-password")
        val adminToken = api().login("admin", "admin-password")

        val trackingResponse = api().get("/api/tracking", userToken)
        trackingResponse.statusCode().shouldBe(200)
        trackingResponse.body().shouldEqualJson(
            """
                {
                  "name": "tracking"
                }
            """.trimIndent(),
        )

        api().get("/api/user-management", userToken).statusCode().shouldBe(403)
        val userManagementResponse = api().get("/api/user-management", adminToken)
        userManagementResponse.statusCode().shouldBe(200)
        userManagementResponse.body().shouldEqualJson(
            """
                {
                  "name": "user-management"
                }
            """.trimIndent(),
        )
    }

    @Test
    fun issuesTokenWithConfiguredExpiration() {
        saveUser("alice", "correct-password", UserType.USER)

        val token = api().login("alice", "correct-password")

        val payloadJson = String(Base64.getUrlDecoder().decode(token.split(".")[1]), StandardCharsets.UTF_8)
        val expiration = payloadJson.replace(Regex(".*\"exp\":([0-9]+).*"), "$1").toLong()
        expiration.shouldBe(testTimeProvider.now().plusSeconds(1800).epochSecond)
    }

    private fun saveUser(username: String, password: String, type: UserType) {
        userRepository.save(User(username = username, passwordHash = passwordHasher.hash(password), type = type))
    }
}
