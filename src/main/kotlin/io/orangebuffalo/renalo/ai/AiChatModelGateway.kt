package io.orangebuffalo.renalo.ai

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.PartialResponse
import dev.langchain4j.model.chat.response.PartialResponseContext
import dev.langchain4j.model.chat.response.PartialToolCall
import dev.langchain4j.model.chat.response.PartialToolCallContext
import dev.langchain4j.model.chat.response.StreamingHandle
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters
import io.micronaut.context.annotation.Requires
import jakarta.inject.Named
import jakarta.inject.Singleton
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicReference

interface AiChatModelGateway {
    fun streamStep(request: AiChatModelStepRequest): Flux<AiChatModelStepEvent>
}

data class AiChatModelStepRequest(
    val previousResponseId: String?,
    val systemPrompt: String,
    val input: List<AiChatModelInput>,
    val toolSpecifications: List<ToolSpecification>,
)

sealed interface AiChatModelInput {
    data class User(val content: String) : AiChatModelInput
    data class ToolResult(val callId: String, val toolName: String, val result: String) : AiChatModelInput
}

sealed interface AiChatModelStepEvent {
    data class TextDelta(val text: String) : AiChatModelStepEvent
    data class Completed(
        val responseId: String,
        val modelAlias: String,
        val toolCalls: List<AiChatModelToolCall>,
    ) : AiChatModelStepEvent
}

data class AiChatModelToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

@Singleton
@Requires(property = "renalo.ai-chat.enabled", value = "true")
@Requires(property = "renalo.ai-chat.litellm.base-url", notEquals = "")
class LangChain4jAiChatModelGateway(
    @Named(AiChatModelFactory.CHAT_MODEL_NAME) private val chatModel: StreamingChatModel,
) : AiChatModelGateway {
    override fun streamStep(request: AiChatModelStepRequest): Flux<AiChatModelStepEvent> = Flux.create { sink ->
        val streamingHandle = AtomicReference<StreamingHandle>()
        sink.onCancel { streamingHandle.get()?.cancel() }
        val parameters = OpenAiResponsesChatRequestParameters.builder()
            .previousResponseId(request.previousResponseId)
            .store(true)
            .strictTools(true)
            .parallelToolCalls(false)
            .toolSpecifications(request.toolSpecifications)
            .build()
        val messages = buildList<ChatMessage> {
            add(SystemMessage.from(request.systemPrompt))
            request.input.forEach { input ->
                add(
                    when (input) {
                        is AiChatModelInput.User -> UserMessage.from(input.content)
                        is AiChatModelInput.ToolResult -> ToolExecutionResultMessage.builder()
                            .id(input.callId)
                            .toolName(input.toolName)
                            .text(input.result)
                            .build()
                    },
                )
            }
        }
        chatModel.chat(
            ChatRequest.builder().messages(messages).parameters(parameters).build(),
            object : StreamingChatResponseHandler {
                override fun onPartialResponse(partialResponse: PartialResponse, context: PartialResponseContext) {
                    streamingHandle.compareAndSet(null, context.streamingHandle())
                    if (!sink.isCancelled && partialResponse.text().isNotEmpty()) {
                        sink.next(AiChatModelStepEvent.TextDelta(partialResponse.text()))
                    }
                }

                override fun onPartialToolCall(partialToolCall: PartialToolCall, context: PartialToolCallContext) {
                    streamingHandle.compareAndSet(null, context.streamingHandle())
                }

                override fun onCompleteResponse(completeResponse: ChatResponse) {
                    if (sink.isCancelled) return
                    val responseId = completeResponse.id()
                        ?: return sink.error(IllegalStateException("LiteLLM response did not include an ID"))
                    val modelAlias = completeResponse.modelName()
                        ?: return sink.error(IllegalStateException("LiteLLM response did not include a model"))
                    val toolCalls = completeResponse.aiMessage().toolExecutionRequests().map { call ->
                        AiChatModelToolCall(call.id(), call.name(), call.arguments())
                    }
                    sink.next(AiChatModelStepEvent.Completed(responseId, modelAlias, toolCalls))
                    sink.complete()
                }

                override fun onError(error: Throwable) {
                    if (!sink.isCancelled) sink.error(error)
                }
            },
        )
    }
}
