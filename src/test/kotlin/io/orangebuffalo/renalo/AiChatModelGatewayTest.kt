package io.orangebuffalo.renalo

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.orangebuffalo.renalo.ai.AiChatLiteLlmConfiguration
import io.orangebuffalo.renalo.ai.AiChatModelFactory
import io.orangebuffalo.renalo.ai.AiChatModelInput
import io.orangebuffalo.renalo.ai.AiChatModelStepEvent
import io.orangebuffalo.renalo.ai.AiChatModelStepRequest
import io.orangebuffalo.renalo.ai.LangChain4jAiChatModelGateway
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class AiChatModelGatewayTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun storesAndContinuesLiteLlmResponsesWithToolResults() {
        val requestBodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/v1/responses") { exchange ->
                requestBodies += exchange.requestBody.bufferedReader().use { it.readText() }
                val response: ByteArray = if (requestBodies.size == 1) {
                    """data: {"type":"response.output_item.added","output_index":0,"sequence_number":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"get_account_balances","arguments":"","status":"in_progress"}}

data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{}","sequence_number":2}

data: {"type":"response.function_call_arguments.done","item_id":"fc_1","output_index":0,"name":"get_account_balances","arguments":"{}","sequence_number":3}

data: {"type":"response.output_item.done","output_index":0,"sequence_number":4,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"get_account_balances","arguments":"{}","status":"completed"}}

data: {"type":"response.completed","sequence_number":5,"response":{"id":"resp_tool","object":"response","created_at":1785542400,"status":"completed","model":"renalo-chat","output":[],"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

data: [DONE]

""".toByteArray()
                } else {
                    """data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Your balance is AUD 123.45","sequence_number":1}

data: {"type":"response.output_item.done","output_index":0,"sequence_number":2,"item":{"id":"msg_1","type":"message","status":"completed","content":[{"type":"output_text","text":"Your balance is AUD 123.45"}],"role":"assistant"}}

data: {"type":"response.completed","sequence_number":3,"response":{"id":"resp_answer","object":"response","created_at":1785542400,"status":"completed","model":"renalo-chat","output":[],"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

data: [DONE]

""".toByteArray()
                }
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        try {
            val configuration = AiChatLiteLlmConfiguration().apply {
                baseUrl = "http://localhost:${server.address.port}/v1/"
                apiKey = "test-key"
                model = "renalo-chat"
            }
            val gateway = LangChain4jAiChatModelGateway(AiChatModelFactory().chatModel(configuration))
            val specification = ToolSpecification.builder()
                .name("get_account_balances")
                .description("Gets balances")
                .parameters(JsonObjectSchema.builder().additionalProperties(false).build())
                .strict(true)
                .build()

            val firstEvents = gateway.streamStep(
                AiChatModelStepRequest(
                    "Use tools",
                    listOf(AiChatModelInput.User("What is my balance?")),
                    listOf(specification),
                    listOf(userItem("What is my balance?")),
                ),
            ).collectList().block()!!
            val completed = firstEvents.single() as AiChatModelStepEvent.Completed
            completed.responseId.shouldBe("resp_tool")
            completed.toolCalls.single().name.shouldBe("get_account_balances")
            completed.outputItems.shouldHaveSize(1)

            val secondEvents = gateway.streamStep(
                AiChatModelStepRequest(
                    "Use tools",
                    listOf(AiChatModelInput.ToolResult(completed.toolCalls.single().id, "get_account_balances", "[]")),
                    listOf(specification),
                    listOf(
                        userItem("What is my balance?"),
                        completed.outputItems.single(),
                        """{"type":"function_call_output","call_id":"call_1","output":"[]"}""",
                    ),
                ),
            ).collectList().block()!!
            (secondEvents.first() as AiChatModelStepEvent.TextDelta).text.shouldBe("Your balance is AUD 123.45")
            (secondEvents.last() as AiChatModelStepEvent.Completed).responseId.shouldBe("resp_answer")
            (secondEvents.last() as AiChatModelStepEvent.Completed).outputItems.shouldHaveSize(1)

            requestBodies.shouldHaveSize(2)
            val firstRequest = objectMapper.readTree(requestBodies.first())
            firstRequest.path("store").asBoolean().shouldBe(false)
            firstRequest.path("instructions").asText().shouldBe("Use tools")
            firstRequest.path("input").none { it.path("role").asText() == "system" }.shouldBe(true)
            firstRequest.path("input").single().path("role").asText().shouldBe("user")
            firstRequest.path("tools").path(0).path("name").asText().shouldBe("get_account_balances")
            val secondRequest = objectMapper.readTree(requestBodies.last())
            secondRequest.has("previous_response_id").shouldBe(false)
            secondRequest.path("instructions").asText().shouldBe("Use tools")
            secondRequest.path("input").none { it.path("role").asText() == "system" }.shouldBe(true)
            secondRequest.path("input").any {
                it.path("type").asText() == "function_call_output" && it.path("output").asText() == "[]"
            }.shouldBe(true)
        } finally {
            server.stop(0)
        }
    }

    private fun userItem(text: String): String =
        """{"type":"message","role":"user","content":[{"type":"input_text","text":"$text"}]}"""

}
