package io.orangebuffalo.renalo

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.ai.AiChatConversation
import io.orangebuffalo.renalo.ai.AiChatConversationEventService
import io.orangebuffalo.renalo.ai.AiChatConversationRepository
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.tracking.Transaction
import io.orangebuffalo.renalo.tracking.TransactionRepository
import io.orangebuffalo.renalo.tracking.TransactionType
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

    @Inject
    lateinit var conversationEventService: AiChatConversationEventService

    @Inject lateinit var trackingAccountRepository: TrackingAccountRepository
    @Inject lateinit var expenseCategoryRepository: ExpenseCategoryRepository
    @Inject lateinit var transactionRepository: TransactionRepository

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
            """{"v":1,"seq":4,"type":"tool.started","activityId":"call_category_totals","label":"Calculating category totals"}""",
            """{"v":1,"seq":5,"type":"tool.completed","activityId":"call_category_totals","label":"Calculated category totals","status":"COMPLETED"}""",
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
            """{"v":1,"seq":16,"type":"assistant.delta","text":"> This response was generated from Renalo's read-only financial tools."}""",
            """{"v":1,"seq":17,"type":"turn.completed","conversation":${conversationJson(conversation)}}""",
        )
        actualEvents.shouldHaveSize(expectedEvents.size)
        actualEvents.zip(expectedEvents).forEach { (actual, expected) -> actual.shouldEqualJson(expected) }
        conversation.title.shouldBe("Monthly spending review")
        conversation.modelAlias.shouldBe("renalo-chat")
        conversation.version.shouldBe(3)
        (createdUpdatedAt < titleUpdatedAt).shouldBe(true)
        (titleUpdatedAt < conversation.updatedAt).shouldBe(true)
    }

    @Test
    fun streamsAndReloadsAValidatedChart() {
        val user = saveUser("alice", UserType.USER)
        val account = trackingAccountRepository.save(
            TrackingAccount(userId = user.id!!, name = "Main", currency = "AUD", initialBalanceMinor = 0, isDefault = true),
        )
        val category = expenseCategoryRepository.save(ExpenseCategory(userId = user.id!!, name = "Groceries"))
        transactionRepository.save(
            Transaction(
                userId = user.id!!,
                type = TransactionType.EXPENSE,
                trackingAccountId = account.id!!,
                categoryId = category.id!!,
                date = java.time.LocalDate.parse("2026-08-01"),
                amountMinor = 2_345,
                defaultCurrencyAmountMinor = 2_345,
                defaultCurrency = "AUD",
            ),
        )
        val token = api().login("alice", "password")

        val response = api().postJson(
            "/api/ai-chat/messages",
            """{"content":"Show a chart of spending"}""",
            token,
        )

        response.statusCode().shouldBe(200)
        val events = response.body().lineSequence().filter(String::isNotBlank).toList()
        val conversation = conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.id!!).single()
        val chartId = objectMapper.readTree(events[7]).path("chart").path("id").asText()
        events.map { objectMapper.readTree(it).path("type").asText() }.shouldBe(
            listOf(
                "conversation.created",
                "conversation.updated",
                "turn.started",
                "tool.started",
                "tool.completed",
                "tool.started",
                "tool.completed",
                "assistant.chart",
                "assistant.delta",
                "assistant.delta",
                "assistant.delta",
                "assistant.delta",
                "assistant.delta",
                "assistant.delta",
                "assistant.delta",
                "assistant.delta",
                "assistant.delta",
                "assistant.delta",
                "assistant.delta",
                "turn.completed",
            ),
        )
        events[7].shouldEqualJson(
            """
                {
                  "v":1,
                  "seq":8,
                  "type":"assistant.chart",
                  "chart":{
                    "id":"$chartId",
                    "kind":"DONUT",
                    "title":"Expenses by category",
                    "currency":"AUD",
                    "series":[],
                    "segments":[{"label":"Groceries","amountMinor":"2345"}]
                  }
                }
            """.trimIndent(),
        )

        api().get("/api/ai-chat/conversations/${conversation.id}/history", token).body().shouldEqualJson(
            """
                {
                  "status":"AVAILABLE",
                  "messages":[
                    {"role":"USER","content":"Show a chart of spending","charts":[]},
                    {
                      "role":"ASSISTANT",
                      "content":"## Spending snapshot\n\nYou asked: **Show a chart of spending**\n\nHere is an example of how an AI-generated answer could present your results:\n\n| Category | Amount | Share |\n| --- | ---: | ---: |\n| Groceries | ${'$'}428.30 | 42% |\n| Transport | ${'$'}186.75 | 18% |\n| Dining out | ${'$'}142.10 | 14% |\n\n- **Groceries** were the largest expense category.\n- Dining out was lower than groceries by `${'$'}286.20`.\n- The remaining categories accounted for 26% of the sample total.\n\n> This response was generated from Renalo's read-only financial tools.",
                      "charts":[{
                        "id":"$chartId",
                        "kind":"DONUT",
                        "title":"Expenses by category",
                        "currency":"AUD",
                        "series":[],
                        "segments":[{"label":"Groceries","amountMinor":"2345"}]
                      }]
                    }
                  ]
                }
            """.trimIndent(),
        )
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
            """{"v":1,"seq":4,"type":"tool.started","activityId":"call_category_totals","label":"Calculating category totals"}""",
            """{"v":1,"seq":5,"type":"tool.completed","activityId":"call_category_totals","label":"Calculated category totals","status":"COMPLETED"}""",
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
            """{"v":1,"seq":16,"type":"assistant.delta","text":"> This response was generated from Renalo's read-only financial tools."}""",
            """{"v":1,"seq":17,"type":"turn.completed","conversation":${conversationJson(conversation)}}""",
        )
        events.shouldHaveSize(expectedEvents.size)
        events.zip(expectedEvents).forEach { (actual, expected) -> actual.shouldEqualJson(expected) }
        conversation.title.shouldBe("New chat")
    }

    @Test
    fun returnsARecoverableEventWhenTheModelIsUnavailable() {
        val user = saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        val response = api().postJson(
            "/api/ai-chat/messages",
            """{ "content": "Please fail model processing" }""",
            token,
        )

        response.statusCode().shouldBe(200)
        val events = response.body().lineSequence().filter(String::isNotBlank).toList()
        events.last().shouldEqualJson(
            """
                {
                  "v": 1,
                  "seq": 4,
                  "type": "turn.error",
                  "code": "AI_UNAVAILABLE",
                  "message": "The AI response is temporarily unavailable. Please try again.",
                  "recoverable": true
                }
            """.trimIndent(),
        )
        val conversation = conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.id!!).single()
        conversation.modelAlias.shouldBe(null)
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
    fun loadsConversationHistoryFromThePersistedEventLog() {
        val user = saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")
        val available = conversationRepository.save(
            AiChatConversation(
                userId = user.id!!,
                title = "Available chat",
            ),
        )
        val empty = conversationRepository.save(
            AiChatConversation(
                userId = user.id!!,
                title = "Empty chat",
            ),
        )
        conversationEventService.appendItems(
            user.id!!,
            available.id!!,
            listOf(
                conversationEventService.userMessage("What did we discuss in this chat?"),
                """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"## Saved conversation\n\nThis history was loaded from Renalo's event log."}]}""",
            ),
        )

        api().get("/api/ai-chat/conversations/${available.id}/history", token).body().shouldEqualJson(
            """
                {
                  "status": "AVAILABLE",
                  "messages": [
                    {
                      "role": "USER",
                      "content": "What did we discuss in this chat?",
                      "charts": []
                    },
                    {
                      "role": "ASSISTANT",
                      "content": "## Saved conversation\n\nThis history was loaded from Renalo's event log.",
                      "charts": []
                    }
                  ]
                }
            """.trimIndent(),
        )
        api().get("/api/ai-chat/conversations/${empty.id}/history", token).body().shouldEqualJson(
            """
                {
                  "status": "AVAILABLE",
                  "messages": []
                }
            """.trimIndent(),
        )
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
    fun rejectsInvalidBrowserTimeZones() {
        saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        api().postJson(
            "/api/ai-chat/messages",
            """{ "content": "What is my balance?" }""",
            token,
            "not-a-time-zone",
        ).statusCode().shouldBe(400)
        conversationRepository.findByUserIdOrderByUpdatedAtDesc(userRepository.findByUsername("alice")!!.id!!)
            .shouldHaveSize(0)
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
