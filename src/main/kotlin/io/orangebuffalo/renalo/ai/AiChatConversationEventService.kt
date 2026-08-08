package io.orangebuffalo.renalo.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.Types
import java.util.UUID
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
    open fun beginTurn(userId: Long, conversationId: Long, content: String): BegunAiChatTurn? {
        if (conversationRepository.findByIdAndUserId(conversationId, userId) == null) return null
        val connection = dataSource.connection
        val existingItems = selectNodes(connection, conversationId)
        check(pendingTopicChanges(existingItems).isEmpty()) { "Resolve the pending AI chat topic change first" }
        val replayItems = modelReplayItems(existingItems)
        val pendingCallIds = linkedSetOf<String>()
        replayItems.forEach { itemJson ->
            val item = objectMapper.readTree(itemJson)
            when (item.path("type").asText()) {
                "function_call" -> item.path("call_id").asText().takeIf(String::isNotBlank)?.let(pendingCallIds::add)
                "function_call_output" -> pendingCallIds.remove(item.path("call_id").asText())
            }
        }
        val turnId = UUID.randomUUID().toString()
        val newItems = buildList {
            add(turnStart(turnId))
            pendingCallIds.forEach { callId -> add(interruptedToolResult(callId)) }
            add(userMessage(content))
        }
        insertItems(connection, conversationId, newItems)
        return BegunAiChatTurn(
            turnId = turnId,
            conversationItems = replayItems + newItems.filterNot { isInternalItem(objectMapper.readTree(it)) },
            allowTopicChangeRecommendation = existingItems.any(::isCompletedAssistantMessage),
        )
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
        return modelReplayItems(selectNodes(dataSource.connection, conversationId))
    }

    private fun selectNodes(connection: Connection, conversationId: Long): List<JsonNode> {
        val items =
        connection.prepareStatement(
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
                    while (result.next()) add(objectMapper.readTree(result.getString(1)))
                }
            }
        }
        return withoutRedirectedTurns(items)
    }

    private fun modelReplayItems(items: List<JsonNode>): List<String> = items
        .filterNot(::isInternalItem)
        .map(objectMapper::writeValueAsString)

    @Transactional(readOnly = true)
    open fun loadHistory(userId: Long, conversationId: Long): AiChatConversationHistoryResponse? {
        if (conversationRepository.findByIdAndUserId(conversationId, userId) == null) return null
        val items = selectNodes(dataSource.connection, conversationId)
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

    @Transactional
    open fun registerTopicChange(
        userId: Long,
        conversationId: Long,
        turnId: String,
        callId: String,
        content: String,
        metrics: AiChatTopicChangeMetrics,
    ): String {
        check(conversationRepository.findByIdAndUserId(conversationId, userId) != null) {
            "AI chat conversation no longer exists"
        }
        val topicChangeId = UUID.randomUUID().toString()
        insertItems(
            dataSource.connection,
            conversationId,
            listOf(
                objectMapper.writeValueAsString(
                    mapOf(
                        "type" to TOPIC_CHANGE_TYPE,
                        "id" to topicChangeId,
                        "turnId" to turnId,
                        "callId" to callId,
                        "content" to content,
                        "durationMillis" to metrics.durationMillis,
                        "tokensConsumed" to metrics.tokensConsumed,
                        "contextTokens" to metrics.contextTokens,
                    ),
                ),
            ),
        )
        return topicChangeId
    }

    @Transactional(readOnly = true)
    open fun hasPendingTopicChange(userId: Long, conversationId: Long, topicChangeId: String): Boolean {
        if (conversationRepository.findByIdAndUserId(conversationId, userId) == null) return false
        return pendingTopicChanges(selectNodes(dataSource.connection, conversationId)).any { it.id == topicChangeId }
    }

    @Transactional
    open fun continueTopicChange(
        userId: Long,
        conversationId: Long,
        topicChangeId: String,
    ): AiChatTopicChangeContinuation? {
        val connection = dataSource.connection
        if (!lockConversation(connection, userId, conversationId)) return null
        val pending = pendingTopicChanges(selectNodes(connection, conversationId))
            .singleOrNull { it.id == topicChangeId } ?: return null
        val output = objectMapper.writeValueAsString(
            mapOf(
                "decision" to "continue_here",
                "instruction" to "Continue answering this request. Do not recommend a new chat again for this request.",
            ),
        )
        insertItems(
            connection,
            conversationId,
            listOf(
                functionCallOutput(pending.callId, output),
                topicChangeDecision(pending, "CONTINUE_HERE"),
            ),
        )
        return AiChatTopicChangeContinuation(
            content = pending.content,
            turnId = pending.turnId,
            toolResult = AiChatModelInput.ToolResult(pending.callId, AiChatTools.RECOMMEND_NEW_CHAT, output),
            conversationItems = modelReplayItems(selectNodes(connection, conversationId)),
            metrics = pending.metrics,
        )
    }

    @Transactional
    open fun redirectTopicChangeToNewConversation(
        userId: Long,
        conversationId: Long,
        topicChangeId: String,
    ): RedirectedAiChatTopicChange? {
        val connection = dataSource.connection
        if (!lockConversation(connection, userId, conversationId)) return null
        val pending = pendingTopicChanges(selectNodes(connection, conversationId))
            .singleOrNull { it.id == topicChangeId } ?: return null
        val saved = conversationRepository.save(
            AiChatConversation(userId = userId, title = AiChatConversationService.DEFAULT_TITLE),
        )
        val destination = conversationRepository.findByIdAndUserId(
            saved.id ?: error("Redirected AI chat conversation must be persisted"),
            userId,
        ) ?: error("Redirected AI chat conversation could not be reloaded")
        insertItems(
            connection,
            conversationId,
            listOf(topicChangeDecision(pending, "NEW_CHAT", destination.id)),
        )
        return RedirectedAiChatTopicChange(destination, pending.content)
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

    private fun functionCallOutput(callId: String, output: String): String = objectMapper.writeValueAsString(
        mapOf(
            "type" to "function_call_output",
            "call_id" to callId,
            "output" to output,
        ),
    )

    private fun turnStart(turnId: String): String = objectMapper.writeValueAsString(
        mapOf("type" to TURN_START_TYPE, "turnId" to turnId),
    )

    private fun topicChangeDecision(
        pending: PendingAiChatTopicChange,
        decision: String,
        destinationConversationId: Long? = null,
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "type" to TOPIC_CHANGE_DECISION_TYPE,
            "topicChangeId" to pending.id,
            "turnId" to pending.turnId,
            "decision" to decision,
            "destinationConversationId" to destinationConversationId,
        ),
    )

    private fun interruptedToolResult(callId: String): String = objectMapper.writeValueAsString(
        mapOf(
            "type" to "function_call_output",
            "call_id" to callId,
            "output" to INTERRUPTED_TOOL_RESULT,
        ),
    )

    private fun lockConversation(connection: Connection, userId: Long, conversationId: Long): Boolean =
        connection.prepareStatement(
            "SELECT id FROM ai_chat_conversations WHERE id = ? AND user_id = ? FOR UPDATE",
        ).use { statement ->
            statement.setLong(1, conversationId)
            statement.setLong(2, userId)
            statement.executeQuery().use { it.next() }
        }

    private fun withoutRedirectedTurns(items: List<JsonNode>): List<JsonNode> {
        val redirectedTurnIds = items.asSequence()
            .filter { it.path("type").asText() == TOPIC_CHANGE_DECISION_TYPE }
            .filter { it.path("decision").asText() == "NEW_CHAT" }
            .map { it.path("turnId").asText() }
            .filter(String::isNotBlank)
            .toSet()
        var currentTurnId: String? = null
        return items.filter { item ->
            if (item.path("type").asText() == TURN_START_TYPE) {
                currentTurnId = item.path("turnId").asText().takeIf(String::isNotBlank)
            }
            currentTurnId !in redirectedTurnIds
        }
    }

    private fun isInternalItem(item: JsonNode): Boolean = item.path("type").asText() in INTERNAL_ITEM_TYPES

    private fun isCompletedAssistantMessage(item: JsonNode): Boolean =
        item.path("type").asText() == "message" &&
            item.path("role").asText() == "assistant" &&
            historyText(item, AiChatHistoryMessageRole.ASSISTANT).isNotBlank()

    private fun pendingTopicChanges(items: List<JsonNode>): List<PendingAiChatTopicChange> {
        val resolvedIds = items.asSequence()
            .filter { it.path("type").asText() == TOPIC_CHANGE_DECISION_TYPE }
            .map { it.path("topicChangeId").asText() }
            .filter(String::isNotBlank)
            .toSet()
        return items.asSequence()
            .filter { it.path("type").asText() == TOPIC_CHANGE_TYPE }
            .filter { it.path("id").asText() !in resolvedIds }
            .map { item ->
                PendingAiChatTopicChange(
                    id = item.path("id").asText(),
                    turnId = item.path("turnId").asText(),
                    callId = item.path("callId").asText(),
                    content = item.path("content").asText(),
                    metrics = AiChatTopicChangeMetrics(
                        durationMillis = item.path("durationMillis").longValue(),
                        tokensConsumed = item.path("tokensConsumed").takeIf(JsonNode::isIntegralNumber)?.longValue(),
                        contextTokens = item.path("contextTokens").takeIf(JsonNode::isIntegralNumber)?.longValue(),
                    ),
                )
            }
            .toList()
    }

    private fun projectHistory(items: List<JsonNode>): List<AiChatHistoryMessageResponse> {
        val messages = mutableListOf<AiChatHistoryMessageResponse>()
        val pendingTopicChangeIds = pendingTopicChanges(items).map(PendingAiChatTopicChange::id).toSet()
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
                    if (
                        toolName != null &&
                        toolName != AiChatTools.RECOMMEND_NEW_CHAT &&
                        output != INTERRUPTED_TOOL_RESULT
                    ) {
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
                TOPIC_CHANGE_TYPE -> {
                    val topicChangeId = item.path("id").asText()
                    if (topicChangeId in pendingTopicChangeIds) {
                        assistantItems += AiChatHistoryTopicChangeResponse(topicChangeId)
                    }
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
        private const val TURN_START_TYPE = "renalo_turn_start"
        private const val TOPIC_CHANGE_TYPE = "renalo_topic_change"
        private const val TOPIC_CHANGE_DECISION_TYPE = "renalo_topic_change_decision"
        private val INTERNAL_ITEM_TYPES = setOf(
            TURN_METRICS_TYPE,
            TURN_START_TYPE,
            TOPIC_CHANGE_TYPE,
            TOPIC_CHANGE_DECISION_TYPE,
        )
    }
}

data class BegunAiChatTurn(
    val turnId: String,
    val conversationItems: List<String>,
    val allowTopicChangeRecommendation: Boolean,
)

data class AiChatTopicChangeMetrics(
    val durationMillis: Long,
    val tokensConsumed: Long?,
    val contextTokens: Long?,
)

data class AiChatTopicChangeContinuation(
    val content: String,
    val turnId: String,
    val toolResult: AiChatModelInput.ToolResult,
    val conversationItems: List<String>,
    val metrics: AiChatTopicChangeMetrics,
)

data class RedirectedAiChatTopicChange(
    val conversation: AiChatConversation,
    val content: String,
)

private data class PendingAiChatTopicChange(
    val id: String,
    val turnId: String,
    val callId: String,
    val content: String,
    val metrics: AiChatTopicChangeMetrics,
)
