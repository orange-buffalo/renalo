package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
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
class AccountActivationApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var userActivationTokenRepository: UserActivationTokenRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun returnsActivationStatusForValidToken() {
        val alice = saveUser("alice", "old-password", active = false)
        saveActivationToken(alice, "valid-token")

        val response = api().get("/api/account-activation?token=valid-token", null)

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "username": "alice"
                }
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsUnknownExpiredAndAlreadyUsedTokens() {
        val alice = saveUser("alice", "old-password", active = false)
        saveActivationToken(alice, "expired-token", expiresAt = Instant.parse("2099-06-14T07:59:59Z"))
        val activeBob = saveUser("bob", "password", active = true)
        saveActivationToken(activeBob, "used-token")

        api().get("/api/account-activation?token=missing-token", null).assertTokenNotFound()
        api().get("/api/account-activation?token=expired-token", null).assertTokenNotFound()
        api().get("/api/account-activation?token=used-token", null).assertTokenNotFound()
        userActivationTokenRepository.findByToken("expired-token").shouldBeNull()
        userActivationTokenRepository.findByToken("used-token").shouldBeNull()
    }

    @Test
    fun activatesAccountWithNewPassword() {
        val alice = saveUser("alice", "old-password", active = false)
        saveActivationToken(alice, "valid-token")

        api().postJson(
            "/api/account-activation?token=valid-token",
            """
                {"password":"new-password","passwordConfirmation":"new-password"}
            """.trimIndent(),
            null,
        ).statusCode().shouldBe(204)

        val activatedUser = userRepository.findByUsername("alice")!!
        activatedUser.active.shouldBe(true)
        passwordHasher.verify("new-password", activatedUser.passwordHash).shouldBe(true)
        userActivationTokenRepository.findByToken("valid-token").shouldBeNull()
        api().login("alice", "new-password").isBlank().shouldBe(false)
    }

    @Test
    fun rejectsInvalidActivationPasswordWithoutUsingToken() {
        val alice = saveUser("alice", "old-password", active = false)
        saveActivationToken(alice, "valid-token")

        val response = api().postJson(
            "/api/account-activation?token=valid-token",
            """
                {"password":"new-password","passwordConfirmation":"different-password"}
            """.trimIndent(),
            null,
        )

        response.statusCode().shouldBe(400)
        val unchangedUser = userRepository.findByUsername("alice")!!
        unchangedUser.active.shouldBe(false)
        passwordHasher.verify("old-password", unchangedUser.passwordHash).shouldBe(true)
        userActivationTokenRepository.findByToken("valid-token")?.token.shouldBe("valid-token")
    }

    private fun saveUser(username: String, password: String, active: Boolean): User {
        return userRepository.save(
            User(
                username = username,
                passwordHash = passwordHasher.hash(password),
                type = UserType.USER,
                active = active,
            ),
        )
    }

    private fun saveActivationToken(
        user: User,
        token: String,
        expiresAt: Instant = Instant.parse("2099-06-15T08:00:00Z"),
    ) {
        userActivationTokenRepository.save(
            UserActivationToken(
                userId = user.id!!,
                token = token,
                expiresAt = expiresAt,
            ),
        )
    }

    private fun java.net.http.HttpResponse<String>.assertTokenNotFound() {
        statusCode().shouldBe(404)
        body().shouldEqualJson(
            """
                {
                  "code": "TOKEN_NOT_FOUND"
                }
            """.trimIndent(),
        )
    }
}
