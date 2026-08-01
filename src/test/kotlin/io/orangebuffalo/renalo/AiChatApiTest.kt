package io.orangebuffalo.renalo

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.ai.AiChatConversation
import io.orangebuffalo.renalo.ai.AiChatConversationRepository
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.Instant

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
@Property(name = "renalo.ai-chat.enabled", value = "true")
class AiChatApiTest : IntegrationTestSupport() {
    private val objectMapper = ObjectMapper()

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var conversationRepository: AiChatConversationRepository

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
        val conversation = conversationRepository.findByUserIdOrderByUpdatedAtDesc(userRepository.findByUsername("alice")!!.id!!)
            .single()
        val createdUpdatedAt = eventUpdatedAt(actualEvents[0])
        val titleUpdatedAt = eventUpdatedAt(actualEvents[1])
        val expectedEvents = listOf(
            """{"v":1,"seq":1,"type":"conversation.created","conversation":${conversationJson(conversation, "New chat", createdUpdatedAt)}}""",
            """{"v":1,"seq":2,"type":"conversation.updated","conversation":${conversationJson(conversation, "Monthly spending review", titleUpdatedAt)}}""",
            """{"v":1,"seq":3,"type":"turn.started"}""",
            """{"v":1,"seq":4,"type":"tool.started","activityId":"activity-1","label":"Reviewing expense totals"}""",
            """{"v":1,"seq":5,"type":"tool.completed","activityId":"activity-1","label":"Reviewed expense totals","status":"COMPLETED"}""",
            """{"v":1,"seq":6,"type":"assistant.delta","text":"## Spending snapshot\n\n"}""",
            """{"v":1,"seq":7,"type":"assistant.delta","text":"You asked: **How was this month?**\n\n"}""",
            """{"v":1,"seq":8,"type":"assistant.delta","text":"Here is an example of how an AI-generated answer could present your results:\n\n"}""",
            """{"v":1,"seq":9,"type":"assistant.delta","text":"| Category | Amount | Share |\n| --- | ---: | ---: |\n"}""",
            """{"v":1,"seq":10,"type":"assistant.delta","text":"| Groceries | ${'$'}428.30 | 42% |\n"}""",
            """{"v":1,"seq":11,"type":"assistant.delta","text":"| Transport | ${'$'}186.75 | 18% |\n"}""",
            """{"v":1,"seq":12,"type":"assistant.delta","text":"| Dining out | ${'$'}142.10 | 14% |\n\n"}""",
            """{"v":1,"seq":13,"type":"assistant.delta","text":"- **Groceries** were the largest expense category.\n"}""",
            """{"v":1,"seq":14,"type":"assistant.delta","text":"- Dining out was lower than groceries by `${'$'}286.20`.\n"}""",
            """{"v":1,"seq":15,"type":"assistant.delta","text":"- The remaining categories accounted for 26% of the sample total.\n\n"}""",
            """{"v":1,"seq":16,"type":"assistant.delta","text":"> This is placeholder data from the Chat preview. It is not calculated from your Renalo records yet."}""",
            """{"v":1,"seq":17,"type":"turn.completed","conversation":${conversationJson(conversation)}}""",
        )
        actualEvents.shouldHaveSize(expectedEvents.size)
        actualEvents.zip(expectedEvents).forEach { (actual, expected) -> actual.shouldEqualJson(expected) }
        conversation.title.shouldBe("Monthly spending review")
        conversation.externalResponseId.shouldBe(null)
        conversation.modelAlias.shouldBe(null)
        conversation.version.shouldBe(2)
        (createdUpdatedAt < titleUpdatedAt).shouldBe(true)
        (titleUpdatedAt < conversation.updatedAt).shouldBe(true)
    }

    @Test
    fun continuesTheTurnWhenTitleGenerationFails() {
        val user = saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        val response = api().postJson(
            "/api/ai-chat/messages",
            """{ "content": "Please fail title generation" }""",
            token,
        )

        response.statusCode().shouldBe(200)
        val events = response.body().lineSequence().filter(String::isNotBlank).toList()
        val conversation = conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.id!!).single()
        val createdAt = eventUpdatedAt(events.first())
        val expectedEvents = listOf(
            """{"v":1,"seq":1,"type":"conversation.created","conversation":${conversationJson(conversation, "New chat", createdAt)}}""",
            """{"v":1,"seq":3,"type":"turn.started"}""",
            """{"v":1,"seq":4,"type":"tool.started","activityId":"activity-1","label":"Reviewing expense totals"}""",
            """{"v":1,"seq":5,"type":"tool.completed","activityId":"activity-1","label":"Reviewed expense totals","status":"COMPLETED"}""",
            """{"v":1,"seq":6,"type":"assistant.delta","text":"## Spending snapshot\n\n"}""",
            """{"v":1,"seq":7,"type":"assistant.delta","text":"You asked: **Please fail title generation**\n\n"}""",
            """{"v":1,"seq":8,"type":"assistant.delta","text":"Here is an example of how an AI-generated answer could present your results:\n\n"}""",
            """{"v":1,"seq":9,"type":"assistant.delta","text":"| Category | Amount | Share |\n| --- | ---: | ---: |\n"}""",
            """{"v":1,"seq":10,"type":"assistant.delta","text":"| Groceries | ${'$'}428.30 | 42% |\n"}""",
            """{"v":1,"seq":11,"type":"assistant.delta","text":"| Transport | ${'$'}186.75 | 18% |\n"}""",
            """{"v":1,"seq":12,"type":"assistant.delta","text":"| Dining out | ${'$'}142.10 | 14% |\n\n"}""",
            """{"v":1,"seq":13,"type":"assistant.delta","text":"- **Groceries** were the largest expense category.\n"}""",
            """{"v":1,"seq":14,"type":"assistant.delta","text":"- Dining out was lower than groceries by `${'$'}286.20`.\n"}""",
            """{"v":1,"seq":15,"type":"assistant.delta","text":"- The remaining categories accounted for 26% of the sample total.\n\n"}""",
            """{"v":1,"seq":16,"type":"assistant.delta","text":"> This is placeholder data from the Chat preview. It is not calculated from your Renalo records yet."}""",
            """{"v":1,"seq":17,"type":"turn.completed","conversation":${conversationJson(conversation)}}""",
        )
        events.shouldHaveSize(expectedEvents.size)
        events.zip(expectedEvents).forEach { (actual, expected) -> actual.shouldEqualJson(expected) }
        conversation.title.shouldBe("New chat")
    }

    @Test
    fun listsRenamesAndDeletesConversations() {
        val user = saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        api().get("/api/ai-chat/conversations", token).body().shouldEqualJson("""{"conversations":[]}""")
        api().postJson(
            "/api/ai-chat/messages",
            """{"content":"First message"}""",
            token,
        ).statusCode().shouldBe(200)
        val created = conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.id!!).single()

        api().get("/api/ai-chat/conversations", token).body().shouldEqualJson(
            """{"conversations":[${conversationJson(created)}]}""",
        )

        val renamedResponse = api().patchJson(
            "/api/ai-chat/conversations/${created.id}",
            """{"title":"  Monthly review  "}""",
            token,
        )
        renamedResponse.statusCode().shouldBe(200)
        val renamed = conversationRepository.findByIdAndUserId(created.id!!, user.id!!)!!
        renamedResponse.body().shouldEqualJson(conversationJson(renamed))
        renamed.title.shouldBe("Monthly review")

        api().patchJson(
            "/api/ai-chat/conversations/${created.id}",
            """{"title":"   "}""",
            token,
        ).statusCode().shouldBe(400)
        api().patchJson(
            "/api/ai-chat/conversations/${created.id}",
            """{"title":"${"x".repeat(101)}"}""",
            token,
        ).statusCode().shouldBe(400)

        api().delete("/api/ai-chat/conversations/${created.id}", token).statusCode().shouldBe(204)
        api().get("/api/ai-chat/conversations", token).body().shouldEqualJson("""{"conversations":[]}""")
    }

    @Test
    fun loadsAvailableAndTemporarilyUnavailableConversationHistory() {
        val user = saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")
        val available = conversationRepository.save(
            AiChatConversation(userId = user.id!!, title = "Available chat"),
        )
        val missing = conversationRepository.save(
            AiChatConversation(userId = user.id!!, title = "Missing provider session"),
        )

        api().get("/api/ai-chat/conversations/${available.id}/history", token).body().shouldEqualJson(
            """
                {
                  "status": "AVAILABLE",
                  "messages": [
                    {
                      "role": "USER",
                      "content": "What did we discuss in this chat?"
                    },
                    {
                      "role": "ASSISTANT",
                      "content": "## Saved conversation\n\nThis preview history was loaded from the simulated external provider."
                    }
                  ]
                }
            """.trimIndent(),
        )
        api().get("/api/ai-chat/conversations/${missing.id}/history", token).body().shouldEqualJson(
            """
                {
                  "status": "TEMPORARILY_UNAVAILABLE",
                  "messages": []
                }
            """.trimIndent(),
        )
        conversationRepository.findByIdAndUserId(missing.id!!, user.id!!)?.title
            .shouldBe("Missing provider session")
    }

    @Test
    fun touchesAndReordersConversationsWhenTurnsStartAndComplete() {
        val user = saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        api().postJson("/api/ai-chat/messages", """{"content":"First question"}""", token).statusCode().shouldBe(200)
        val firstBefore = conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.id!!).single()
        api().postJson("/api/ai-chat/messages", """{"content":"Second question"}""", token).statusCode().shouldBe(200)
        val second = conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.id!!).first()

        val response = api().postJson(
            "/api/ai-chat/messages",
            """{"conversationId":${firstBefore.id},"content":"Continue the first chat"}""",
            token,
        )
        val events = response.body().lineSequence().filter(String::isNotBlank).toList()
        val acceptedAt = eventUpdatedAt(events.first())
        val firstAfter = conversationRepository.findByIdAndUserId(firstBefore.id!!, user.id!!)!!

        events.first().shouldEqualJson(
            """{"v":1,"seq":1,"type":"conversation.updated","conversation":${conversationJson(firstAfter, updatedAt = acceptedAt)}}""",
        )
        events.last().shouldEqualJson(
            """{"v":1,"seq":16,"type":"turn.completed","conversation":${conversationJson(firstAfter)}}""",
        )
        (firstBefore.updatedAt!! < acceptedAt).shouldBe(true)
        (acceptedAt < firstAfter.updatedAt).shouldBe(true)
        api().get("/api/ai-chat/conversations", token).body().shouldEqualJson(
            """{"conversations":[${conversationJson(firstAfter)},${conversationJson(second)}]}""",
        )
    }

    @Test
    fun isolatesConversationOperationsByUser() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val aliceToken = api().login("alice", "password")
        val bobToken = api().login("bob", "password")
        val bobConversation = conversationRepository.save(
            AiChatConversation(userId = bob.id!!, title = "Bob's chat"),
        )

        api().get("/api/ai-chat/conversations", aliceToken).body().shouldEqualJson("""{"conversations":[]}""")
        api().patchJson(
            "/api/ai-chat/conversations/${bobConversation.id}",
            """{"title":"Not Alice's chat"}""",
            aliceToken,
        ).statusCode().shouldBe(404)
        api().delete("/api/ai-chat/conversations/${bobConversation.id}", aliceToken).statusCode().shouldBe(404)
        api().get("/api/ai-chat/conversations/${bobConversation.id}/history", aliceToken).statusCode().shouldBe(404)
        api().postJson(
            "/api/ai-chat/messages",
            """{"conversationId":${bobConversation.id},"content":"Hello"}""",
            aliceToken,
        ).statusCode().shouldBe(404)

        conversationRepository.findByIdAndUserId(bobConversation.id!!, bob.id!!)?.title.shouldBe("Bob's chat")
        conversationRepository.findByUserIdOrderByUpdatedAtDesc(alice.id!!).shouldHaveSize(0)
        api().get("/api/ai-chat/conversations", bobToken).statusCode().shouldBe(200)
    }

    @Test
    fun requiresARegularUser() {
        saveUser("admin", UserType.ADMIN)
        val adminToken = api().login("admin", "password")
        val request = """{ "content": "Hello" }"""

        api().postJson("/api/ai-chat/messages", request, null).statusCode().shouldBe(401)
        api().postJson("/api/ai-chat/messages", request, adminToken).statusCode().shouldBe(403)
        api().get("/api/ai-chat/conversations", null).statusCode().shouldBe(401)
        api().get("/api/ai-chat/conversations", adminToken).statusCode().shouldBe(403)
        api().get("/api/ai-chat/conversations/1/history", null).statusCode().shouldBe(401)
        api().get("/api/ai-chat/conversations/1/history", adminToken).statusCode().shouldBe(403)
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

    private fun conversationJson(
        conversation: AiChatConversation,
        title: String = conversation.title,
        updatedAt: Instant? = conversation.updatedAt,
    ): String =
        """{"id":${conversation.id},"title":"$title","createdAt":"${conversation.createdAt}","updatedAt":"$updatedAt"}"""

    private fun eventUpdatedAt(event: String): Instant = Instant.parse(
        objectMapper.readTree(event).path("conversation").path("updatedAt").asText(),
    )
}
