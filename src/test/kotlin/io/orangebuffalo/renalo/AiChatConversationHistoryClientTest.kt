package io.orangebuffalo.renalo

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.orangebuffalo.renalo.ai.AiChatHistoryMessageResponse
import io.orangebuffalo.renalo.ai.AiChatHistoryMessageRole
import io.orangebuffalo.renalo.ai.AiChatLiteLlmConfiguration
import io.orangebuffalo.renalo.ai.LiteLlmAiChatConversationHistoryClient
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class AiChatConversationHistoryClientTest {
    @Test
    fun reconstructsConversationHistoryFromTheLiteLlmResponseChain() {
        val requests = mutableListOf<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/v1/responses") { exchange ->
                requests.add(exchange.requestURI.toString() to exchange.requestHeaders.getFirst("Authorization"))
                val body = when (exchange.requestURI.toString()) {
                    "/v1/responses/resp_new" -> response("resp_new", "resp_old", "Current answer")
                    "/v1/responses/resp_old" -> response("resp_old", null, "Earlier answer")
                    "/v1/responses/resp_new/input_items?order=asc&limit=100" ->
                        inputItems("msg_user_new", "Current question")
                    "/v1/responses/resp_old/input_items?order=asc&limit=100" ->
                        inputItems("msg_user_old", "Earlier question")
                    else -> error("Unexpected request ${exchange.requestURI}")
                }.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        try {
            val configuration = AiChatLiteLlmConfiguration().apply {
                baseUrl = "http://localhost:${server.address.port}/v1/"
                apiKey = "test-key"
                model = "renalo-chat"
            }
            val client = LiteLlmAiChatConversationHistoryClient(configuration)

            client.loadHistory("resp_new").shouldContainExactly(
                AiChatHistoryMessageResponse(AiChatHistoryMessageRole.USER, "Earlier question"),
                AiChatHistoryMessageResponse(AiChatHistoryMessageRole.ASSISTANT, "Earlier answer"),
                AiChatHistoryMessageResponse(AiChatHistoryMessageRole.USER, "Current question"),
                AiChatHistoryMessageResponse(AiChatHistoryMessageRole.ASSISTANT, "Current answer"),
            )
            requests.map { it.first }.shouldContainExactly(
                "/v1/responses/resp_new",
                "/v1/responses/resp_new/input_items?order=asc&limit=100",
                "/v1/responses/resp_old",
                "/v1/responses/resp_old/input_items?order=asc&limit=100",
            )
            requests.map { it.second }.distinct().single().shouldBe("Bearer test-key")
        } finally {
            server.stop(0)
        }
    }

    private fun response(id: String, previousResponseId: String?, answer: String): String =
        """
            {
              "id": "$id",
              "previous_response_id": ${previousResponseId?.let { "\"$it\"" } ?: "null"},
              "output": [
                {
                  "id": "msg_assistant_$id",
                  "type": "message",
                  "role": "assistant",
                  "content": [{"type": "output_text", "text": "$answer"}]
                },
                {
                  "id": "tool_$id",
                  "type": "function_call",
                  "name": "private_tool_name",
                  "arguments": "{}"
                }
              ]
            }
        """.trimIndent()

    private fun inputItems(id: String, question: String): String =
        """
            {
              "object": "list",
              "data": [
                {
                  "id": "$id",
                  "type": "message",
                  "role": "user",
                  "content": [{"type": "input_text", "text": "$question"}]
                },
                {
                  "id": "tool_output_$id",
                  "type": "function_call_output",
                  "output": "private tool result"
                }
              ],
              "has_more": false
            }
        """.trimIndent()
}
