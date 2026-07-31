package io.orangebuffalo.renalo.ai

import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.security.annotation.Secured
import io.micronaut.json.JsonMapper
import io.orangebuffalo.renalo.auth.UserRoles
import reactor.core.publisher.Flux

@Controller("/api/ai-chat")
@Secured(UserRoles.USER)
@Requires(property = "renalo.ai-chat.enabled", value = "true")
class AiChatController(
    private val aiChatService: AiChatService,
    private val jsonMapper: JsonMapper,
) {
    @Post(value = "/messages", produces = [NDJSON_MEDIA_TYPE])
    fun sendMessage(@Body request: AiChatMessageRequest): HttpResponse<Flux<ByteArray>> {
        if (request.content.isBlank()) {
            return HttpResponse.badRequest()
        }

        val stream = aiChatService.streamMessage(request.content).map { event ->
            jsonMapper.writeValueAsBytes(event) + '\n'.code.toByte()
        }
        return HttpResponse.ok(stream).contentType(MediaType.of(NDJSON_MEDIA_TYPE))
    }

    companion object {
        private const val NDJSON_MEDIA_TYPE = "application/x-ndjson"
    }
}

data class AiChatMessageRequest(
    val content: String,
)

sealed interface AiChatStreamEvent {
    val v: Int
        get() = 1
    val seq: Int
    val type: String
}

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
    override val type: String = "turn.completed",
) : AiChatStreamEvent
