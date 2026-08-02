package io.orangebuffalo.renalo.ai

import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Singleton
class AiChatService(
    private val titleGenerator: AiChatTitleGenerator,
    private val conversationEventService: AiChatConversationEventService,
    private val modelGateway: AiChatModelGateway,
    private val tools: AiChatTools,
    private val conversationService: AiChatConversationService,
    private val turnLock: AiChatConversationTurnLock,
    private val liteLlmConfiguration: AiChatLiteLlmConfiguration,
) {
    private val logger = LoggerFactory.getLogger(AiChatService::class.java)

    fun loadConversationHistory(conversation: AiChatConversation): Mono<AiChatConversationHistoryResponse> {
        return Mono.fromCallable {
            conversationEventService.loadHistory(conversation.userId, conversation.id!!)
                ?: error("AI chat conversation no longer exists")
        }.subscribeOn(Schedulers.boundedElastic())
            .onErrorResume { error ->
                logger.warn("Failed to load history for AI chat conversation {}", conversation.id, error)
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
    ): Flux<AiChatStreamEvent> = Flux.defer {
        val sequence = AtomicInteger(startingSequence)
        val metricsTracker = AiChatTurnMetricsTracker()
        turnLock.withLock(conversationId) {
            Flux.defer {
                val conversation = conversationService.findConversation(userId, conversationId)
                    ?: return@defer Flux.error(IllegalStateException("AI chat conversation no longer exists"))
                val configuredModel = liteLlmConfiguration.model.trim()
                require(configuredModel.isNotEmpty()) { "AI chat LiteLLM model must be configured" }
                check(conversation.modelAlias == null || conversation.modelAlias == configuredModel) {
                    "AI chat conversation model no longer matches the configured model alias"
                }
                val conversationItems = conversationEventService.beginTurn(userId, conversationId, content)
                    ?: return@defer Flux.error(IllegalStateException("AI chat conversation no longer exists"))
                Flux.concat(
                    Flux.just(AiChatTurnStarted(seq = sequence.getAndIncrement())),
                    streamModelStep(
                        userId = userId,
                        conversationId = conversationId,
                        modelAlias = configuredModel,
                        currentDate = currentDate,
                        input = listOf(AiChatModelInput.User(content)),
                        conversationItems = conversationItems,
                        sequence = sequence,
                        toolCallCount = 0,
                        metricsTracker = metricsTracker,
                    ),
                )
            }.subscribeOn(Schedulers.boundedElastic())
        }.takeUntilOther(
            Mono.delay(MAX_TURN_DURATION)
                .then(Mono.error(TimeoutException("AI chat turn exceeded its maximum duration"))),
        ).doOnCancel {
            Schedulers.boundedElastic().schedule {
                persistTurnMetrics(userId, conversationId, metricsTracker)
            }
        }.onErrorResume { error ->
            logger.warn("AI chat turn failed for conversation {}", conversationId, error)
            val completedMetrics = persistTurnMetrics(userId, conversationId, metricsTracker)
            Flux.just(
                AiChatTurnError(
                    seq = sequence.getAndIncrement(),
                    code = "AI_UNAVAILABLE",
                    message = "The AI response is temporarily unavailable. Please try again.",
                    metrics = completedMetrics?.metrics,
                    contextUsage = completedMetrics?.contextUsage,
                ),
            )
        }
    }

    private fun streamModelStep(
        userId: Long,
        conversationId: Long,
        modelAlias: String,
        currentDate: LocalDate,
        input: List<AiChatModelInput>,
        conversationItems: List<String>,
        sequence: AtomicInteger,
        toolCallCount: Int,
        metricsTracker: AiChatTurnMetricsTracker,
    ): Flux<AiChatStreamEvent> = modelGateway.streamStep(
        AiChatModelStepRequest(
            systemPrompt = systemPrompt(currentDate),
            input = input,
            toolSpecifications = tools.specifications,
            conversationItems = conversationItems,
        ),
    ).concatMap { event ->
        when (event) {
            is AiChatModelStepEvent.TextDelta -> Flux.just(
                AiChatAssistantDelta(seq = sequence.getAndIncrement(), text = event.text),
            )
            is AiChatModelStepEvent.Completed -> {
                metricsTracker.record(event.tokenUsage)
                conversationEventService.appendItems(userId, conversationId, event.outputItems)
                conversationService.setModelAlias(
                    userId,
                    conversationId,
                    modelAlias,
                )
                if (event.toolCalls.isEmpty()) {
                    val completedMetrics = persistTurnMetrics(userId, conversationId, metricsTracker)
                    Flux.just(
                        AiChatTurnCompleted(
                            seq = sequence.getAndIncrement(),
                            metrics = completedMetrics?.metrics,
                            contextUsage = completedMetrics?.contextUsage,
                        ),
                    )
                } else {
                    check(toolCallCount + event.toolCalls.size <= MAX_TOOL_CALLS) { "AI chat tool call limit exceeded" }
                    executeTools(
                        userId,
                        conversationId,
                        modelAlias,
                        currentDate,
                        event.toolCalls,
                        sequence,
                        toolCallCount + event.toolCalls.size,
                        metricsTracker,
                    )
                }
            }
        }
    }

    private fun executeTools(
        userId: Long,
        conversationId: Long,
        modelAlias: String,
        currentDate: LocalDate,
        calls: List<AiChatModelToolCall>,
        sequence: AtomicInteger,
        toolCallCount: Int,
        metricsTracker: AiChatTurnMetricsTracker,
    ): Flux<AiChatStreamEvent> {
        val results = mutableListOf<AiChatModelInput.ToolResult>()
        return Flux.fromIterable(calls).concatMap { call ->
            val activity = tools.activity(call)
            Flux.concat(
                Flux.just<AiChatStreamEvent>(AiChatToolStarted(sequence.getAndIncrement(), call.id, activity.first)),
                Mono.delay(MIN_TOOL_ACTIVITY_DURATION)
                    .then(Mono.fromCallable { tools.execute(userId, currentDate, call) })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany { executed ->
                        results += AiChatModelInput.ToolResult(call.id, call.name, executed.result)
                        conversationEventService.appendItems(
                            userId,
                            conversationId,
                            listOf(conversationEventService.toolResult(call, executed.result)),
                        )
                        Flux.fromIterable(
                            buildList<AiChatStreamEvent> {
                                add(AiChatToolCompleted(sequence.getAndIncrement(), executed.activityId, executed.completedLabel))
                                executed.chart?.let { chart ->
                                    add(AiChatAssistantChart(sequence.getAndIncrement(), chart))
                                }
                            },
                        )
                    },
            )
        }.concatWith(
            Flux.defer {
                Flux.just(AiChatAssistantThinking(sequence.getAndIncrement(), "Reviewing results"))
            },
        ).concatWith(
            Flux.defer {
                val conversationItems = conversationEventService.loadItems(userId, conversationId)
                    ?: return@defer Flux.error(IllegalStateException("AI chat conversation no longer exists"))
                streamModelStep(
                    userId,
                    conversationId,
                    modelAlias,
                    currentDate,
                    results,
                    conversationItems,
                    sequence,
                    toolCallCount,
                    metricsTracker,
                )
            },
        )
    }

    private fun persistTurnMetrics(
        userId: Long,
        conversationId: Long,
        metricsTracker: AiChatTurnMetricsTracker,
    ): CompletedTurnMetrics? {
        val snapshot = metricsTracker.finish() ?: return null
        val metrics = AiChatTurnMetricsResponse(snapshot.durationMillis, snapshot.tokensConsumed)
        runCatching {
            conversationEventService.appendTurnMetrics(userId, conversationId, metrics, snapshot.contextTokens)
        }.onFailure { error ->
            logger.warn("Failed to persist metrics for AI chat conversation {}", conversationId, error)
        }
        return CompletedTurnMetrics(
            metrics = metrics,
            contextUsage = snapshot.contextTokens?.let(conversationEventService::contextUsage),
        )
    }

    private fun systemPrompt(currentDate: LocalDate): String = """
        You are Renalo's personal-finance assistant. Today is $currentDate.
        Use the provided read-only tools for every claim about the user's Renalo data; never invent values.
        Tool amounts are integer minor units in the accompanying ISO currency. Format them using that currency's fraction digits.
        Use transaction query summaries for complete-set analytics and explicit ordering for rankings. Paginate when the answer requires individual rows beyond one result page.
        Prefer presenting a chart whenever one can answer or materially clarify the user's question. First obtain authoritative data, then choose the most useful grouping, series, axes, and chart style. Pass only exact values from successful tools to the chart tool; never invent or estimate chart data.
        State when search results are truncated or currency conversion data is unavailable.
        Answer concisely in Markdown. Do not reveal tool names, arguments, raw JSON, internal IDs, or hidden reasoning.
    """.trimIndent()

    companion object {
        private const val MAX_TOOL_CALLS = 64
        private val MIN_TOOL_ACTIVITY_DURATION = Duration.ofMillis(500)
        private val MAX_TURN_DURATION = Duration.ofMinutes(15)
    }
}

private data class CompletedTurnMetrics(
    val metrics: AiChatTurnMetricsResponse,
    val contextUsage: AiChatContextUsageResponse?,
)

private data class AiChatTurnMetricsSnapshot(
    val durationMillis: Long,
    val tokensConsumed: Long?,
    val contextTokens: Long?,
)

private class AiChatTurnMetricsTracker {
    private val startedAtNanos = System.nanoTime()
    private val finished = AtomicBoolean()
    private val tokensConsumed = AtomicReference<Long?>(0L)
    private val hasTokenUsage = AtomicBoolean()
    private val contextTokens = AtomicReference<Long>()

    fun record(usage: AiChatModelTokenUsage?) {
        if (usage == null) return
        val stepTotal = usage.totalTokens?.takeIf { it >= 0 } ?: usage.inputTokens?.takeIf { it >= 0 }?.let { input ->
            usage.outputTokens?.takeIf { it >= 0 }?.let { output ->
                runCatching { Math.addExact(input, output) }.getOrNull()
            }
        }
        if (stepTotal != null) {
            tokensConsumed.updateAndGet { current ->
                current?.let { runCatching { Math.addExact(it, stepTotal) }.getOrNull() }
            }
            contextTokens.set(stepTotal)
            hasTokenUsage.set(true)
        }
    }

    fun finish(): AiChatTurnMetricsSnapshot? {
        if (!finished.compareAndSet(false, true)) return null
        return AiChatTurnMetricsSnapshot(
            durationMillis = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis(),
            tokensConsumed = tokensConsumed.get()?.takeIf { hasTokenUsage.get() },
            contextTokens = contextTokens.get(),
        )
    }
}
