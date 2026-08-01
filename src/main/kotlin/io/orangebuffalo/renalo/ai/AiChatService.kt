package io.orangebuffalo.renalo.ai

import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@Singleton
class AiChatService(
    private val titleGenerator: AiChatTitleGenerator,
    private val conversationHistoryClient: AiChatConversationHistoryClient,
    private val modelGateway: AiChatModelGateway,
    private val tools: AiChatTools,
    private val conversationService: AiChatConversationService,
    private val turnLock: AiChatConversationTurnLock,
    private val liteLlmConfiguration: AiChatLiteLlmConfiguration,
) {
    private val logger = LoggerFactory.getLogger(AiChatService::class.java)

    fun loadConversationHistory(conversation: AiChatConversation): Mono<AiChatConversationHistoryResponse> {
        val externalResponseId = conversation.externalResponseId
            ?: return Mono.just(
                AiChatConversationHistoryResponse(
                    status = AiChatConversationHistoryStatus.AVAILABLE,
                    messages = emptyList(),
                ),
            )
        return Mono.fromCallable {
            AiChatConversationHistoryResponse(
                status = AiChatConversationHistoryStatus.AVAILABLE,
                messages = conversationHistoryClient.loadHistory(externalResponseId),
            )
        }.subscribeOn(Schedulers.boundedElastic())
            .onErrorResume { error ->
                logger.warn("Failed to load external history for AI chat conversation {}", conversation.id, error)
                Mono.just(
                    AiChatConversationHistoryResponse(
                        status = AiChatConversationHistoryStatus.TEMPORARILY_UNAVAILABLE,
                        messages = emptyList(),
                    ),
                )
            }
    }

    fun generateTitle(content: String): Mono<String> = Mono.fromCallable {
        titleGenerator.generateTitle(content)
    }

    fun streamMessage(
        userId: Long,
        conversationId: Long,
        content: String,
        currentDate: LocalDate,
        startingSequence: Int = 1,
    ): Flux<AiChatStreamEvent> {
        val sequence = AtomicInteger(startingSequence)
        return turnLock.withLock(conversationId) {
            Flux.defer {
                val conversation = conversationService.findConversation(userId, conversationId)
                    ?: return@defer Flux.error(IllegalStateException("AI chat conversation no longer exists"))
                val configuredModel = liteLlmConfiguration.model.trim()
                require(configuredModel.isNotEmpty()) { "AI chat LiteLLM model must be configured" }
                check(conversation.modelAlias == null || conversation.modelAlias == configuredModel) {
                    "AI chat conversation model no longer matches the configured model alias"
                }
                conversation.externalResponseId?.let(conversationHistoryClient::loadHistory)

                Flux.concat(
                    Flux.just(AiChatTurnStarted(seq = sequence.getAndIncrement())),
                    streamModelStep(
                        userId = userId,
                        conversationId = conversationId,
                        expectedResponseId = conversation.externalResponseId,
                        modelAlias = configuredModel,
                        currentDate = currentDate,
                        input = listOf(AiChatModelInput.User(content)),
                        sequence = sequence,
                        toolCallCount = 0,
                    ),
                )
            }.subscribeOn(Schedulers.boundedElastic())
        }.onErrorResume { error ->
            logger.warn("AI chat turn failed for conversation {}", conversationId, error)
            Flux.just(
                AiChatTurnError(
                    seq = sequence.getAndIncrement(),
                    code = "AI_UNAVAILABLE",
                    message = "The AI response is temporarily unavailable. Please try again.",
                ),
            )
        }
    }

    private fun streamModelStep(
        userId: Long,
        conversationId: Long,
        expectedResponseId: String?,
        modelAlias: String,
        currentDate: LocalDate,
        input: List<AiChatModelInput>,
        sequence: AtomicInteger,
        toolCallCount: Int,
    ): Flux<AiChatStreamEvent> = modelGateway.streamStep(
        AiChatModelStepRequest(
            previousResponseId = expectedResponseId,
            systemPrompt = systemPrompt(currentDate),
            input = input,
            toolSpecifications = tools.specifications,
        ),
    ).concatMap { event ->
        when (event) {
            is AiChatModelStepEvent.TextDelta -> Flux.just(
                AiChatAssistantDelta(seq = sequence.getAndIncrement(), text = event.text),
            )
            is AiChatModelStepEvent.Completed -> {
                conversationService.updateExternalState(
                    userId,
                    conversationId,
                    expectedResponseId,
                    event.responseId,
                    modelAlias,
                )
                if (event.toolCalls.isEmpty()) {
                    Flux.just(AiChatTurnCompleted(seq = sequence.getAndIncrement()))
                } else {
                    check(toolCallCount + event.toolCalls.size <= MAX_TOOL_CALLS) { "AI chat tool call limit exceeded" }
                    executeTools(
                        userId,
                        conversationId,
                        event.responseId,
                        modelAlias,
                        currentDate,
                        event.toolCalls,
                        sequence,
                        toolCallCount + event.toolCalls.size,
                    )
                }
            }
        }
    }

    private fun executeTools(
        userId: Long,
        conversationId: Long,
        previousResponseId: String,
        modelAlias: String,
        currentDate: LocalDate,
        calls: List<AiChatModelToolCall>,
        sequence: AtomicInteger,
        toolCallCount: Int,
    ): Flux<AiChatStreamEvent> {
        val results = mutableListOf<AiChatModelInput.ToolResult>()
        return Flux.fromIterable(calls).concatMap { call ->
            val activity = tools.activity(call)
            Flux.concat(
                Flux.just(AiChatToolStarted(sequence.getAndIncrement(), call.id, activity.first)),
                Mono.delay(MIN_TOOL_ACTIVITY_DURATION)
                    .then(Mono.fromCallable { tools.execute(userId, currentDate, call) })
                    .subscribeOn(Schedulers.boundedElastic())
                    .map { executed ->
                        results += AiChatModelInput.ToolResult(call.id, call.name, executed.result)
                        AiChatToolCompleted(sequence.getAndIncrement(), executed.activityId, executed.completedLabel)
                    },
            )
        }.concatWith(
            Flux.defer {
                streamModelStep(
                    userId,
                    conversationId,
                    previousResponseId,
                    modelAlias,
                    currentDate,
                    results,
                    sequence,
                    toolCallCount,
                )
            },
        )
    }

    private fun systemPrompt(currentDate: LocalDate): String = """
        You are Renalo's personal-finance assistant. Today is $currentDate.
        Use the provided read-only tools for every claim about the user's Renalo data; never invent values.
        Tool amounts are integer minor units in the accompanying ISO currency. Format them using that currency's fraction digits.
        State when search results are truncated or currency conversion data is unavailable.
        Answer concisely in Markdown. Do not reveal tool names, arguments, raw JSON, internal IDs, or hidden reasoning.
    """.trimIndent()

    companion object {
        private const val MAX_TOOL_CALLS = 12
        private val MIN_TOOL_ACTIVITY_DURATION = Duration.ofMillis(500)
    }
}
