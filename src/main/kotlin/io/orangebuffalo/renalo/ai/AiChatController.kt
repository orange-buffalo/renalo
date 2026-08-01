package io.orangebuffalo.renalo.ai

import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.Post
import io.micronaut.json.JsonMapper
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.user.UserRepository
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
    private val userRepository: UserRepository,
    private val jsonMapper: JsonMapper,
) {
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
    ): HttpResponse<*> {
        if (request.content.isBlank()) {
            return HttpResponse.badRequest<Any>()
        }

        return withUser(authentication) { userId ->
            when (val result = conversationService.prepareConversation(userId, request.conversationId)) {
                PrepareAiChatConversationResult.NotFound -> HttpResponse.notFound<Any>()
                is PrepareAiChatConversationResult.Prepared -> {
                    val conversationId = result.conversation.id
                        ?: error("Prepared AI chat conversation must be persisted")
                    val initialMetadata = if (result.wasCreated) {
                        Flux.just(
                            AiChatConversationCreated(
                                seq = 1,
                                conversation = result.conversation.toResponse(),
                            ),
                        )
                    } else {
                        Flux.just(
                            AiChatConversationUpdated(
                                seq = 1,
                                conversation = result.conversation.toResponse(),
                            ),
                        )
                    }
                    val generatedTitle = if (result.wasCreated) {
                        aiChatService.generateTitle(request.content)
                            .flatMap { title ->
                                Mono.fromCallable {
                                    conversationService.updateGeneratedTitle(userId, conversationId, title)
                                }.subscribeOn(Schedulers.boundedElastic())
                            }
                            .map { conversation ->
                                AiChatConversationUpdated(
                                    seq = 2,
                                    conversation = conversation.toResponse(),
                                )
                            }
                            .flux()
                    } else {
                        Flux.empty()
                    }
                    val firstTurnSequence = if (result.wasCreated) 3 else 2
                    val turnEvents = aiChatService.streamMessage(request.content, firstTurnSequence)
                        .concatMap { event ->
                            if (event is AiChatTurnCompleted) {
                                Mono.fromCallable {
                                    val conversation = conversationService.completeTurn(userId, conversationId)
                                    event.copy(conversation = conversation.toResponse())
                                }.subscribeOn(Schedulers.boundedElastic())
                            } else {
                                Mono.just(event)
                            }
                        }
                    val stream = Flux.concat(
                        initialMetadata,
                        generatedTitle,
                        turnEvents,
                    ).map { event ->
                        jsonMapper.writeValueAsBytes(event) + '\n'.code.toByte()
                    }
                    HttpResponse.ok(stream).contentType(MediaType.of(NDJSON_MEDIA_TYPE))
                }
            }
        }
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
)

enum class AiChatConversationHistoryStatus {
    AVAILABLE,
    TEMPORARILY_UNAVAILABLE,
}

data class AiChatHistoryMessageResponse(
    val role: AiChatHistoryMessageRole,
    val content: String,
)

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

data class AiChatTurnCompleted(
    override val seq: Int,
    val conversation: AiChatConversationResponse? = null,
    override val type: String = "turn.completed",
) : AiChatStreamEvent
