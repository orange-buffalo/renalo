package io.orangebuffalo.renalo.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Types
import javax.sql.DataSource

@Singleton
open class AiChatConversationEventService(
    private val dataSource: DataSource,
    private val conversationRepository: AiChatConversationRepository,
    private val charts: AiChatCharts,
    private val tools: AiChatTools,
    private val liteLlmConfiguration: AiChatLiteLlmConfiguration,
) {
    private val objectMapper = ObjectMapper()

    @Transactional
    open fun appendItems(userId: Long, conversationId: Long, items: List<String>) {
        if (items.isEmpty()) return
        check(conversationRepository.findByIdAndUserId(conversationId, userId) != null) {
            "AI chat conversation no longer exists"
        }
        val connection = dataSource.connection
        insertItems(connection, conversationId, items)
    }

    @Transactional
    open fun beginTurn(userId: Long, conversationId: Long, content: String): List<String>? {
        if (conversationRepository.findByIdAndUserId(conversationId, userId) == null) return null
        val connection = dataSource.connection
        val existingItems = selectItems(connection, conversationId, includeInternalItems = false)
        val pendingCallIds = linkedSetOf<String>()
        existingItems.forEach { itemJson ->
            val item = objectMapper.readTree(itemJson)
            when (item.path("type").asText()) {
                "function_call" -> item.path("call_id").asText().takeIf(String::isNotBlank)?.let(pendingCallIds::add)
                "function_call_output" -> pendingCallIds.remove(item.path("call_id").asText())
            }
        }
        val newItems = buildList {
            pendingCallIds.forEach { callId -> add(interruptedToolResult(callId)) }
            add(userMessage(content))
        }
        insertItems(connection, conversationId, newItems)
        return existingItems + newItems
    }

    private fun insertItems(connection: Connection, conversationId: Long, items: List<String>) {
        if (items.isEmpty()) return
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
        return selectItems(dataSource.connection, conversationId, includeInternalItems = false)
    }

    private fun selectItems(
        connection: Connection,
        conversationId: Long,
        includeInternalItems: Boolean,
    ): List<String> =
        connection.prepareStatement(
            """
                SELECT item::text
                FROM ai_chat_conversation_events
                WHERE conversation_id = ?
                ${if (includeInternalItems) "" else "AND item_type <> '$TURN_METRICS_TYPE'"}
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

    @Transactional(readOnly = true)
    open fun loadHistory(userId: Long, conversationId: Long): AiChatConversationHistoryResponse? {
        if (conversationRepository.findByIdAndUserId(conversationId, userId) == null) return null
        val items = selectItems(dataSource.connection, conversationId, includeInternalItems = true)
            .map(objectMapper::readTree)
        val currentContextTokens = items.lastOrNull { it.path("type").asText() == TURN_METRICS_TYPE }
            ?.path("contextTokens")
            ?.takeIf(JsonNode::isIntegralNumber)
            ?.longValue()
        return AiChatConversationHistoryResponse(
            status = AiChatConversationHistoryStatus.AVAILABLE,
            messages = projectHistory(items),
            contextUsage = currentContextTokens?.let(::contextUsage),
        )
    }

    @Transactional
    open fun appendTurnMetrics(
        userId: Long,
        conversationId: Long,
        metrics: AiChatTurnMetricsResponse,
        contextTokens: Long?,
    ) {
        appendItems(
            userId,
            conversationId,
            listOf(
                objectMapper.writeValueAsString(
                    mapOf(
                        "type" to TURN_METRICS_TYPE,
                        "durationMillis" to metrics.durationMillis,
                        "tokensConsumed" to metrics.tokensConsumed,
                        "contextTokens" to contextTokens,
                    ),
                ),
            ),
        )
    }

    fun contextUsage(currentTokens: Long): AiChatContextUsageResponse = AiChatContextUsageResponse(
        currentTokens = currentTokens,
        maxTokens = liteLlmConfiguration.maxContextTokens.takeIf { it > 0 },
    )

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

    private fun interruptedToolResult(callId: String): String = objectMapper.writeValueAsString(
        mapOf(
            "type" to "function_call_output",
            "call_id" to callId,
            "output" to INTERRUPTED_TOOL_RESULT,
        ),
    )

    private fun projectHistory(items: List<JsonNode>): List<AiChatHistoryMessageResponse> {
        val messages = mutableListOf<AiChatHistoryMessageResponse>()
        val callNames = mutableMapOf<String, String>()
        val assistantContent = StringBuilder()
        val assistantCharts = mutableListOf<AiChatChartResponse>()
        val assistantItems = mutableListOf<AiChatHistoryItemResponse>()
        var assistantMetrics: AiChatTurnMetricsResponse? = null

        fun flushAssistant() {
            if (assistantContent.isEmpty() && assistantCharts.isEmpty() && assistantItems.isEmpty()) return
            messages += AiChatHistoryMessageResponse(
                role = AiChatHistoryMessageRole.ASSISTANT,
                content = assistantContent.toString(),
                charts = assistantCharts.toList(),
                items = assistantItems.toList(),
                metrics = assistantMetrics,
            )
            assistantContent.clear()
            assistantCharts.clear()
            assistantItems.clear()
            assistantMetrics = null
        }

        items.forEach { item ->
            when (item.path("type").asText()) {
                "function_call" -> callNames[item.path("call_id").asText()] = item.path("name").asText()
                "function_call_output" -> {
                    val output = item.path("output").asText()
                    val toolName = callNames[item.path("call_id").asText()]
                    if (toolName != null && output != INTERRUPTED_TOOL_RESULT) {
                        assistantItems += AiChatHistoryToolActivityResponse(tools.completedActivityLabel(toolName))
                        if (toolName == AiChatCharts.PRESENT_CHART_TOOL) {
                            charts.decodeArtifact(output)?.let { chart ->
                                assistantCharts += chart
                                assistantItems += AiChatHistoryChartResponse(chart)
                            }
                        }
                    }
                }
                "message" -> {
                    val role = historyRole(item) ?: return@forEach
                    val content = historyText(item, role)
                    if (role == AiChatHistoryMessageRole.USER) {
                        flushAssistant()
                        if (content.isNotBlank()) {
                            messages += AiChatHistoryMessageResponse(
                                role = role,
                                content = content,
                            )
                        }
                    } else if (content.isNotBlank()) {
                        assistantContent.append(content)
                        assistantItems += AiChatHistoryContentResponse(content)
                    }
                }
                TURN_METRICS_TYPE -> {
                    assistantMetrics = AiChatTurnMetricsResponse(
                        durationMillis = item.path("durationMillis").longValue(),
                        tokensConsumed = item.path("tokensConsumed")
                            .takeIf(JsonNode::isIntegralNumber)
                            ?.longValue(),
                    )
                }
            }
        }
        flushAssistant()
        return messages
    }

    private fun historyRole(item: JsonNode): AiChatHistoryMessageRole? {
        val role = when (item.path("role").asText()) {
            "user" -> AiChatHistoryMessageRole.USER
            "assistant" -> AiChatHistoryMessageRole.ASSISTANT
            else -> return null
        }
        return role
    }

    private fun historyText(item: JsonNode, role: AiChatHistoryMessageRole): String {
        val textTypes = if (role == AiChatHistoryMessageRole.USER) setOf("input_text") else setOf("output_text", "refusal")
        return item.path("content").mapNotNull { part ->
            when (part.path("type").asText()) {
                in textTypes -> part.path("text").asText().takeIf(String::isNotBlank)
                    ?: part.path("refusal").asText().takeIf(String::isNotBlank)
                else -> null
            }
        }.joinToString("")
    }

    companion object {
        private const val INTERRUPTED_TOOL_RESULT =
            "{\"error\":\"The tool execution was interrupted before a result was available. Fetch fresh data if needed.\"}"
        private const val TURN_METRICS_TYPE = "renalo_turn_metrics"
    }
}
