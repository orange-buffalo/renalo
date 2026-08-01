package io.orangebuffalo.renalo.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

interface AiChatConversationHistoryClient {
    fun loadHistory(latestResponseId: String): List<AiChatHistoryMessageResponse>
}

@Singleton
@Requires(property = "renalo.ai-chat.enabled", value = "true")
@Requires(property = "renalo.ai-chat.litellm.base-url", notEquals = "")
class LiteLlmAiChatConversationHistoryClient(
    configuration: AiChatLiteLlmConfiguration,
) : AiChatConversationHistoryClient {
    private val baseUrl = configuration.baseUrl.required("base URL").removeSuffix("/")
    private val apiKey = configuration.apiKey.required("API key")
    private val objectMapper = ObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    override fun loadHistory(latestResponseId: String): List<AiChatHistoryMessageResponse> {
        val turns = mutableListOf<List<AiChatHistoryMessageResponse>>()
        val visitedResponseIds = mutableSetOf<String>()
        var responseId: String? = latestResponseId

        while (responseId != null) {
            check(turns.size < MAX_RESPONSES) { "LiteLLM response chain exceeds the supported limit" }
            check(visitedResponseIds.add(responseId)) { "LiteLLM response chain contains a cycle" }

            val response = getJson("responses/${encode(responseId)}")
            val messages = loadInputItems(responseId).mapNotNull(::toUserMessage) +
                response.path("output").mapNotNull(::toAssistantMessage)
            turns.add(messages)
            responseId = response.path("previous_response_id").takeUnless(JsonNode::isNull)?.asText()
                ?.takeIf(String::isNotBlank)
        }

        return turns.asReversed().flatten()
    }

    private fun loadInputItems(responseId: String): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        var after: String? = null
        var pageCount = 0
        do {
            check(pageCount++ < MAX_INPUT_PAGES) { "LiteLLM response input exceeds the supported limit" }
            val query = buildString {
                append("order=asc&limit=")
                append(INPUT_PAGE_SIZE)
                after?.let { append("&after=").append(encode(it)) }
            }
            val page = getJson("responses/${encode(responseId)}/input_items?$query")
            val pageItems = page.path("data")
            check(pageItems.isArray) { "LiteLLM input items response is malformed" }
            items.addAll(pageItems)
            after = if (page.path("has_more").asBoolean(false)) {
                page.path("last_id").asText().takeIf(String::isNotBlank)
                    ?: error("LiteLLM input items page has no last item ID")
            } else {
                null
            }
        } while (after != null)
        return items
    }

    private fun toUserMessage(item: JsonNode): AiChatHistoryMessageResponse? {
        if (item.path("type").asText() != "message" || item.path("role").asText() != "user") return null
        return textMessage(item, "input_text", AiChatHistoryMessageRole.USER)
    }

    private fun toAssistantMessage(item: JsonNode): AiChatHistoryMessageResponse? {
        if (item.path("type").asText() != "message" || item.path("role").asText() != "assistant") return null
        return textMessage(item, "output_text", AiChatHistoryMessageRole.ASSISTANT)
    }

    private fun textMessage(
        item: JsonNode,
        textType: String,
        role: AiChatHistoryMessageRole,
    ): AiChatHistoryMessageResponse? {
        val content = item.path("content")
        val text = when {
            content.isTextual -> content.asText()
            content.isArray -> content.mapNotNull { part ->
                when (part.path("type").asText()) {
                    textType -> part.path("text").asText()
                    "refusal" -> part.path("refusal").asText()
                    else -> null
                }
            }.joinToString("")
            else -> ""
        }
        return text.takeIf(String::isNotBlank)?.let { AiChatHistoryMessageResponse(role, it) }
    }

    private fun getJson(path: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/$path"))
            .timeout(READ_TIMEOUT)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() in 200..299) {
            "LiteLLM history lookup failed with HTTP ${response.statusCode()}"
        }
        return objectMapper.readTree(response.body())
    }

    private fun String.required(name: String): String = trim().takeIf(String::isNotEmpty)
        ?: error("AI chat LiteLLM $name must be configured")

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        private const val INPUT_PAGE_SIZE = 100
        private const val MAX_INPUT_PAGES = 100
        private const val MAX_RESPONSES = 100
        private val CONNECT_TIMEOUT = Duration.ofSeconds(5)
        private val READ_TIMEOUT = Duration.ofSeconds(20)
    }
}
