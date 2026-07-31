package io.orangebuffalo.renalo.ai

import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.security.annotation.Secured
import io.orangebuffalo.renalo.auth.UserRoles

@Controller("/api/ai-chat")
@Secured(UserRoles.USER)
@Requires(property = "renalo.ai-chat.enabled", value = "true")
class AiChatController(
    private val aiChatService: AiChatService,
) {
    @Post("/messages")
    fun sendMessage(@Body request: AiChatMessageRequest): HttpResponse<AiChatMessageResponse> {
        if (request.content.isBlank()) {
            return HttpResponse.badRequest()
        }

        return HttpResponse.ok(AiChatMessageResponse(content = aiChatService.sendMessage(request.content)))
    }
}

data class AiChatMessageRequest(
    val content: String,
)

data class AiChatMessageResponse(
    val content: String,
)
