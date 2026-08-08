package io.orangebuffalo.renalo.ai

import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.Post
import io.micronaut.json.JsonMapper
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.time.CLIENT_TIME_ZONE_HEADER
import io.orangebuffalo.renalo.time.TimeProvider
import io.orangebuffalo.renalo.time.parseClientTimeZone
import io.orangebuffalo.renalo.user.UserRepository
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Instant

@Controller("/api/ai-chat")
@Secured(UserRoles.USER)
@Requires(property = "renalo.ai-chat.enabled", value = "true")
class AiChatController(
    private val aiChatService: AiChatService,
    private val conversationService: AiChatConversationService,
    private val conversationEventService: AiChatConversationEventService,
    private val userRepository: UserRepository,
    private val jsonMapper: JsonMapper,
    private val timeProvider: TimeProvider,
) {
    private val logger = LoggerFactory.getLogger(AiChatController::class.java)

    @Get("/conversations")
    fun listConversations(authentication: Authentication): HttpResponse<*> = withUser(authentication) { userId ->
        HttpResponse.ok(
            AiChatConversationsResponse(
                conversations = conversationService.listConversations(userId).map { it.toResponse() },
            ),
        )
    }

    @Get("/conversations/{conversationId}/history")
    fun loadConversationHistory(
        conversationId: Long,
        authentication: Authentication,
    ): Mono<HttpResponse<*>> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return Mono.just(HttpResponse.unauthorized<Any>())
        val conversation = conversationService.findConversation(user.id!!, conversationId)
            ?: return Mono.just(HttpResponse.notFound<Any>())
        return aiChatService.loadConversationHistory(conversation)
            .map<HttpResponse<*>> { HttpResponse.ok(it) }
    }

    @Patch("/conversations/{conversationId}")
    fun renameConversation(
        conversationId: Long,
        @Body request: RenameAiChatConversationRequest,
        authentication: Authentication,
    ): HttpResponse<*> = withUser(authentication) { userId ->
        when (val result = conversationService.renameConversation(userId, conversationId, request.title)) {
            is SaveAiChatConversationResult.Saved -> HttpResponse.ok(result.conversation.toResponse())
            SaveAiChatConversationResult.BadRequest -> HttpResponse.badRequest<Any>()
            SaveAiChatConversationResult.NotFound -> HttpResponse.notFound<Any>()
        }
    }

    @Delete("/conversations/{conversationId}")
    fun deleteConversation(conversationId: Long, authentication: Authentication): HttpResponse<*> =
        withUser(authentication) { userId ->
            when (conversationService.deleteConversation(userId, conversationId)) {
                DeleteAiChatConversationResult.Deleted -> HttpResponse.noContent<Any>()
                DeleteAiChatConversationResult.NotFound -> HttpResponse.notFound<Any>()
            }
        }

    @Post(value = "/messages", produces = [NDJSON_MEDIA_TYPE])
    fun sendMessage(
        @Body request: AiChatMessageRequest,
        authentication: Authentication,
        @Header(CLIENT_TIME_ZONE_HEADER) timeZone: String?,
    ): HttpResponse<*> {
        if (request.content.isBlank()) {
            return HttpResponse.badRequest<Any>()
        }

        val clientTimeZone = parseClientTimeZone(timeZone) ?: return HttpResponse.badRequest<Any>()
        val currentDate = timeProvider.today(clientTimeZone)
        return withUser(authentication) { userId ->
            when (val result = conversationService.prepareConversation(userId, request.conversationId)) {
                PrepareAiChatConversationResult.NotFound -> HttpResponse.notFound<Any>()
                is PrepareAiChatConversationResult.Prepared -> streamResponse(
                    prepared = result,
                    userId = userId,
                    content = request.content,
                    currentDate = currentDate,
                )
            }
        }
    }

    @Post(
        value = "/conversations/{conversationId}/topic-changes/{topicChangeId}/continue",
        produces = [NDJSON_MEDIA_TYPE],
    )
    fun continueTopicChange(
        conversationId: Long,
        topicChangeId: String,
        authentication: Authentication,
        @Header(CLIENT_TIME_ZONE_HEADER) timeZone: String?,
    ): HttpResponse<*> {
        val clientTimeZone = parseClientTimeZone(timeZone) ?: return HttpResponse.badRequest<Any>()
        val currentDate = timeProvider.today(clientTimeZone)
        return withUser(authentication) { userId ->
            if (!conversationEventService.hasPendingTopicChange(userId, conversationId, topicChangeId)) {
                HttpResponse.notFound<Any>()
            } else {
                val events = aiChatService.continueTopicChange(userId, conversationId, topicChangeId, currentDate)
                    .withCompletedConversation(userId, conversationId)
                ndjsonResponse(events)
            }
        }
    }

    @Post(
        value = "/conversations/{conversationId}/topic-changes/{topicChangeId}/new-chat",
        produces = [NDJSON_MEDIA_TYPE],
    )
    fun continueTopicChangeInNewChat(
        conversationId: Long,
        topicChangeId: String,
        authentication: Authentication,
        @Header(CLIENT_TIME_ZONE_HEADER) timeZone: String?,
    ): HttpResponse<*> {
        val clientTimeZone = parseClientTimeZone(timeZone) ?: return HttpResponse.badRequest<Any>()
        val currentDate = timeProvider.today(clientTimeZone)
        return withUser(authentication) { userId ->
            val redirected = conversationEventService.redirectTopicChangeToNewConversation(
                userId,
                conversationId,
                topicChangeId,
            ) ?: return@withUser HttpResponse.notFound<Any>()
            streamResponse(
                prepared = PrepareAiChatConversationResult.Prepared(redirected.conversation, wasCreated = true),
                userId = userId,
                content = redirected.content,
                currentDate = currentDate,
            )
        }
    }

    private fun streamResponse(
        prepared: PrepareAiChatConversationResult.Prepared,
        userId: Long,
        content: String,
        currentDate: java.time.LocalDate,
    ): HttpResponse<*> {
        val conversationId = prepared.conversation.id
            ?: error("Prepared AI chat conversation must be persisted")
        val initialMetadata: Flux<AiChatStreamEvent> = if (prepared.wasCreated) {
            Flux.just(AiChatConversationCreated(seq = 1, conversation = prepared.conversation.toResponse()))
        } else {
            Flux.just(AiChatConversationUpdated(seq = 1, conversation = prepared.conversation.toResponse()))
        }
        val generatedTitle: Flux<AiChatStreamEvent> = if (prepared.wasCreated) {
            aiChatService.generateTitle(content)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap { title ->
                    Mono.fromCallable {
                        conversationService.updateGeneratedTitle(userId, conversationId, title)
                    }.subscribeOn(Schedulers.boundedElastic())
                }
                .map<AiChatStreamEvent> { conversation ->
                    AiChatConversationUpdated(seq = 2, conversation = conversation.toResponse())
                }
                .doOnError { error ->
                    logger.warn("Failed to generate title for AI chat conversation {}", conversationId, error)
                }
                .onErrorResume { Mono.empty() }
                .flux()
        } else {
            Flux.empty()
        }
        val firstTurnSequence = if (prepared.wasCreated) 3 else 2
        val turnEvents = aiChatService.streamMessage(
            userId,
            conversationId,
            content,
            currentDate,
            firstTurnSequence,
        ).withCompletedConversation(userId, conversationId)
        return ndjsonResponse(Flux.concat(initialMetadata, generatedTitle, turnEvents))
    }

    private fun Flux<AiChatStreamEvent>.withCompletedConversation(
        userId: Long,
        conversationId: Long,
    ): Flux<AiChatStreamEvent> = concatMap { event ->
        if (event is AiChatTurnCompleted) {
            Mono.fromCallable {
                val conversation = conversationService.findConversation(userId, conversationId)
                    ?: error("AI chat conversation disappeared while its turn was completing")
                event.copy(conversation = conversation.toResponse())
            }.subscribeOn(Schedulers.boundedElastic())
        } else {
            Mono.just(event)
        }
    }

    private fun ndjsonResponse(events: Flux<AiChatStreamEvent>): HttpResponse<*> {
        val stream = events.map { event -> jsonMapper.writeValueAsBytes(event) + '\n'.code.toByte() }
        return HttpResponse.ok(stream).contentType(MediaType.of(NDJSON_MEDIA_TYPE))
    }

    private fun withUser(authentication: Authentication, action: (Long) -> HttpResponse<*>): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        return action(user.id!!)
    }

    companion object {
        private const val NDJSON_MEDIA_TYPE = "application/x-ndjson"
    }
}

data class AiChatMessageRequest(
    val content: String,
    val conversationId: Long? = null,
)

data class RenameAiChatConversationRequest(val title: String)

data class AiChatConversationsResponse(
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val conversations: List<AiChatConversationResponse>,
)

data class AiChatConversationResponse(
    val id: Long,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AiChatConversationHistoryResponse(
    val status: AiChatConversationHistoryStatus,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val messages: List<AiChatHistoryMessageResponse>,
    val contextUsage: AiChatContextUsageResponse? = null,
)

enum class AiChatConversationHistoryStatus {
    AVAILABLE,
    TEMPORARILY_UNAVAILABLE,
}

data class AiChatHistoryMessageResponse(
    val role: AiChatHistoryMessageRole,
    val content: String,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val charts: List<AiChatChartResponse> = emptyList(),
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val items: List<AiChatHistoryItemResponse> = emptyList(),
    val metrics: AiChatTurnMetricsResponse? = null,
)

data class AiChatTurnMetricsResponse(
    val durationMillis: Long,
    val tokensConsumed: Long?,
)

data class AiChatContextUsageResponse(
    val currentTokens: Long,
    val maxTokens: Long?,
)

sealed interface AiChatHistoryItemResponse {
    val type: String
}

data class AiChatHistoryContentResponse(
    val content: String,
    override val type: String = "CONTENT",
) : AiChatHistoryItemResponse

data class AiChatHistoryChartResponse(
    val chart: AiChatChartResponse,
    override val type: String = "CHART",
) : AiChatHistoryItemResponse

data class AiChatHistoryToolActivityResponse(
    val label: String,
    override val type: String = "TOOL_ACTIVITY",
) : AiChatHistoryItemResponse

data class AiChatHistoryTopicChangeResponse(
    val topicChangeId: String,
    override val type: String = "TOPIC_CHANGE",
) : AiChatHistoryItemResponse

enum class AiChatHistoryMessageRole {
    USER,
    ASSISTANT,
}

private fun AiChatConversation.toResponse() = AiChatConversationResponse(
    id = id ?: error("AI chat conversation must be persisted before it can be returned"),
    title = title,
    createdAt = createdAt ?: error("AI chat conversation must have a creation time"),
    updatedAt = updatedAt ?: error("AI chat conversation must have an update time"),
)

sealed interface AiChatStreamEvent {
    val v: Int
        get() = 1
    val seq: Int
    val type: String
}

data class AiChatConversationCreated(
    override val seq: Int,
    val conversation: AiChatConversationResponse,
    override val type: String = "conversation.created",
) : AiChatStreamEvent

data class AiChatConversationUpdated(
    override val seq: Int,
    val conversation: AiChatConversationResponse,
    override val type: String = "conversation.updated",
) : AiChatStreamEvent

data class AiChatTurnStarted(
    override val seq: Int,
    override val type: String = "turn.started",
) : AiChatStreamEvent

data class AiChatToolStarted(
    override val seq: Int,
    val activityId: String,
    val label: String,
    override val type: String = "tool.started",
) : AiChatStreamEvent

data class AiChatToolCompleted(
    override val seq: Int,
    val activityId: String,
    val label: String,
    val status: String = "COMPLETED",
    override val type: String = "tool.completed",
) : AiChatStreamEvent

data class AiChatAssistantDelta(
    override val seq: Int,
    val text: String,
    override val type: String = "assistant.delta",
) : AiChatStreamEvent

data class AiChatAssistantChart(
    override val seq: Int,
    val chart: AiChatChartResponse,
    override val type: String = "assistant.chart",
) : AiChatStreamEvent

data class AiChatAssistantThinking(
    override val seq: Int,
    val label: String,
    override val type: String = "assistant.thinking",
) : AiChatStreamEvent

data class AiChatTopicChangeSuggested(
    override val seq: Int,
    val topicChangeId: String,
    override val type: String = "topic_change.suggested",
) : AiChatStreamEvent

data class AiChatTopicChangeResolved(
    override val seq: Int,
    val topicChangeId: String,
    override val type: String = "topic_change.resolved",
) : AiChatStreamEvent

data class AiChatTurnCompleted(
    override val seq: Int,
    val conversation: AiChatConversationResponse? = null,
    val metrics: AiChatTurnMetricsResponse? = null,
    val contextUsage: AiChatContextUsageResponse? = null,
    override val type: String = "turn.completed",
) : AiChatStreamEvent

data class AiChatTurnError(
    override val seq: Int,
    val code: String,
    val message: String,
    val recoverable: Boolean = true,
    val metrics: AiChatTurnMetricsResponse? = null,
    val contextUsage: AiChatContextUsageResponse? = null,
    override val type: String = "turn.error",
) : AiChatStreamEvent
