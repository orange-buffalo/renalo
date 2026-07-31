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

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
@Property(name = "renalo.ai-chat.enabled", value = "true")
class AiChatApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun echoesMessagesForRegularUsers() {
        saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        val response = api().postJson(
            "/api/ai-chat/messages",
            """{ "content": "How was this month?" }""",
            token,
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "content": "You asked: How was this month?\n\nLorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.\n\nDuis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum."
                }
            """.trimIndent(),
        )
    }

    @Test
    fun requiresARegularUser() {
        saveUser("admin", UserType.ADMIN)
        val adminToken = api().login("admin", "password")
        val request = """{ "content": "Hello" }"""

        api().postJson("/api/ai-chat/messages", request, null).statusCode().shouldBe(401)
        api().postJson("/api/ai-chat/messages", request, adminToken).statusCode().shouldBe(403)
    }

    @Test
    fun rejectsBlankMessages() {
        saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        api().postJson("/api/ai-chat/messages", """{ "content": "   " }""", token).statusCode().shouldBe(400)
    }

    @Test
    fun exposesEnabledStateThroughSystemSettings() {
        saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        val response = api().get("/api/system-settings", token)

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "publicUrl": "http://localhost:8080",
                  "aiChatEnabled": true
                }
            """.trimIndent(),
        )
    }

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = type,
        ),
    )
}
