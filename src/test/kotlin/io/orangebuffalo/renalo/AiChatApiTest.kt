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
    fun returnsMarkdownAndStructuredToolActivityForRegularUsers() {
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
                  "content": "## Spending snapshot\n\nYou asked: **How was this month?**\n\nHere is an example of how an AI-generated answer could present your results:\n\n| Category | Amount | Share |\n| --- | ---: | ---: |\n| Groceries | ${'$'}428.30 | 42% |\n| Transport | ${'$'}186.75 | 18% |\n| Dining out | ${'$'}142.10 | 14% |\n\n- **Groceries** were the largest expense category.\n- Dining out was lower than groceries by `${'$'}286.20`.\n- The remaining categories accounted for 26% of the sample total.\n\n> This is placeholder data from the Chat preview. It is not calculated from your Renalo records yet.",
                  "toolActivities": [
                    {
                      "label": "Reviewed expense totals",
                      "status": "COMPLETED"
                    }
                  ]
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
