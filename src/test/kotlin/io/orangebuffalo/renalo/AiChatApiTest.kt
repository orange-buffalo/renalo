package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
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
    fun streamsMarkdownAndStructuredToolActivityForRegularUsers() {
        saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        val response = api().postJson(
            "/api/ai-chat/messages",
            """{ "content": "How was this month?" }""",
            token,
        )

        response.statusCode().shouldBe(200)
        response.headers().firstValue("content-type").orElseThrow().shouldStartWith("application/x-ndjson")
        val actualEvents = response.body().lineSequence().filter(String::isNotBlank).toList()
        val expectedEvents = listOf(
            """{"v":1,"seq":1,"type":"turn.started"}""",
            """{"v":1,"seq":2,"type":"tool.started","activityId":"activity-1","label":"Reviewing expense totals"}""",
            """{"v":1,"seq":3,"type":"tool.completed","activityId":"activity-1","label":"Reviewed expense totals","status":"COMPLETED"}""",
            """{"v":1,"seq":4,"type":"assistant.delta","text":"## Spending snapshot\n\n"}""",
            """{"v":1,"seq":5,"type":"assistant.delta","text":"You asked: **How was this month?**\n\n"}""",
            """{"v":1,"seq":6,"type":"assistant.delta","text":"Here is an example of how an AI-generated answer could present your results:\n\n"}""",
            """{"v":1,"seq":7,"type":"assistant.delta","text":"| Category | Amount | Share |\n| --- | ---: | ---: |\n"}""",
            """{"v":1,"seq":8,"type":"assistant.delta","text":"| Groceries | ${'$'}428.30 | 42% |\n"}""",
            """{"v":1,"seq":9,"type":"assistant.delta","text":"| Transport | ${'$'}186.75 | 18% |\n"}""",
            """{"v":1,"seq":10,"type":"assistant.delta","text":"| Dining out | ${'$'}142.10 | 14% |\n\n"}""",
            """{"v":1,"seq":11,"type":"assistant.delta","text":"- **Groceries** were the largest expense category.\n"}""",
            """{"v":1,"seq":12,"type":"assistant.delta","text":"- Dining out was lower than groceries by `${'$'}286.20`.\n"}""",
            """{"v":1,"seq":13,"type":"assistant.delta","text":"- The remaining categories accounted for 26% of the sample total.\n\n"}""",
            """{"v":1,"seq":14,"type":"assistant.delta","text":"> This is placeholder data from the Chat preview. It is not calculated from your Renalo records yet."}""",
            """{"v":1,"seq":15,"type":"turn.completed"}""",
        )
        actualEvents.shouldHaveSize(expectedEvents.size)
        actualEvents.zip(expectedEvents).forEach { (actual, expected) -> actual.shouldEqualJson(expected) }
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
