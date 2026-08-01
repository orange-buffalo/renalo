package io.orangebuffalo.renalo.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.sql.Types
import javax.sql.DataSource

@Singleton
open class AiChatConversationEventService(
    private val dataSource: DataSource,
    private val conversationRepository: AiChatConversationRepository,
) {
    private val objectMapper = ObjectMapper()

    @Transactional
    open fun appendItems(userId: Long, conversationId: Long, items: List<String>) {
        if (items.isEmpty()) return
        check(conversationRepository.findByIdAndUserId(conversationId, userId) != null) {
            "AI chat conversation no longer exists"
        }
        val connection = dataSource.connection
        val nextSequence = connection.prepareStatement(
            "SELECT COALESCE(MAX(sequence), 0) + 1 FROM ai_chat_conversation_events WHERE conversation_id = ?",
        ).use { statement ->
            statement.setLong(1, conversationId)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getLong(1)
            }
        }
        connection.prepareStatement(
            """
                INSERT INTO ai_chat_conversation_events (conversation_id, sequence, item_type, item)
                VALUES (?, ?, ?, ?::jsonb)
            """.trimIndent(),
        ).use { statement ->
            items.forEachIndexed { index, itemJson ->
                val item = objectMapper.readTree(itemJson)
                val itemType = item.path("type").asText().takeIf(String::isNotBlank)
                    ?: error("AI chat conversation item has no type")
                statement.setLong(1, conversationId)
                statement.setLong(2, nextSequence + index)
                statement.setString(3, itemType)
                statement.setObject(4, itemJson, Types.OTHER)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    @Transactional(readOnly = true)
    open fun loadItems(userId: Long, conversationId: Long): List<String>? {
        if (conversationRepository.findByIdAndUserId(conversationId, userId) == null) return null
        return dataSource.connection.prepareStatement(
            """
                SELECT item::text
                FROM ai_chat_conversation_events
                WHERE conversation_id = ?
                ORDER BY sequence
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, conversationId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.getString(1))
                }
            }
        }
    }

    fun loadHistory(userId: Long, conversationId: Long): AiChatConversationHistoryResponse? {
        val items = loadItems(userId, conversationId) ?: return null
        return AiChatConversationHistoryResponse(
            status = AiChatConversationHistoryStatus.AVAILABLE,
            messages = items.mapNotNull { itemJson -> toHistoryMessage(objectMapper.readTree(itemJson)) },
        )
    }

    fun userMessage(content: String): String = objectMapper.writeValueAsString(
        mapOf(
            "type" to "message",
            "role" to "user",
            "content" to listOf(mapOf("type" to "input_text", "text" to content)),
        ),
    )

    fun toolResult(call: AiChatModelToolCall, result: String): String = objectMapper.writeValueAsString(
        mapOf(
            "type" to "function_call_output",
            "call_id" to call.id,
            "output" to result,
        ),
    )

    private fun toHistoryMessage(item: JsonNode): AiChatHistoryMessageResponse? {
        if (item.path("type").asText() != "message") return null
        val role = when (item.path("role").asText()) {
            "user" -> AiChatHistoryMessageRole.USER
            "assistant" -> AiChatHistoryMessageRole.ASSISTANT
            else -> return null
        }
        val textTypes = if (role == AiChatHistoryMessageRole.USER) setOf("input_text") else setOf("output_text", "refusal")
        val content = item.path("content").mapNotNull { part ->
            when (part.path("type").asText()) {
                in textTypes -> part.path("text").asText().takeIf(String::isNotBlank)
                    ?: part.path("refusal").asText().takeIf(String::isNotBlank)
                else -> null
            }
        }.joinToString("")
        return content.takeIf(String::isNotBlank)?.let { AiChatHistoryMessageResponse(role, it) }
    }
}
