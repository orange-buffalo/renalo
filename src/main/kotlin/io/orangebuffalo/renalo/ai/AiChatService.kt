package io.orangebuffalo.renalo.ai

import jakarta.inject.Singleton
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

@Singleton
class AiChatService {
    fun loadConversationHistory(conversation: AiChatConversation): Mono<AiChatConversationHistoryResponse> {
        val response = if (conversation.title.contains("missing", ignoreCase = true)) {
            AiChatConversationHistoryResponse(
                status = AiChatConversationHistoryStatus.TEMPORARILY_UNAVAILABLE,
                messages = emptyList(),
            )
        } else {
            AiChatConversationHistoryResponse(
                status = AiChatConversationHistoryStatus.AVAILABLE,
                messages = listOf(
                    AiChatHistoryMessageResponse(
                        role = AiChatHistoryMessageRole.USER,
                        content = "What did we discuss in this chat?",
                    ),
                    AiChatHistoryMessageResponse(
                        role = AiChatHistoryMessageRole.ASSISTANT,
                        content = "## Saved conversation\n\nThis preview history was loaded from the simulated external provider.",
                    ),
                ),
            )
        }
        return Mono.just(response).delayElement(HISTORY_LOADING_DELAY)
    }

    fun generateTitle(content: String): Mono<String> = Mono.just(
        when {
            content.contains("month", ignoreCase = true) -> "Monthly spending review"
            content.contains("spend", ignoreCase = true) -> "Spending review"
            else -> "Financial overview"
        },
    ).delayElement(TITLE_GENERATION_DELAY)

    fun streamMessage(content: String, startingSequence: Int = 1): Flux<AiChatStreamEvent> {
        val events = buildList {
            var sequence = startingSequence
            add(AiChatTurnStarted(seq = sequence++))
            add(
                AiChatToolStarted(
                    seq = sequence++,
                    activityId = TOOL_ACTIVITY_ID,
                    label = "Reviewing expense totals",
                ),
            )
            add(
                AiChatToolCompleted(
                    seq = sequence++,
                    activityId = TOOL_ACTIVITY_ID,
                    label = "Reviewed expense totals",
                ),
            )
            responseChunks(content).forEach { chunk ->
                add(AiChatAssistantDelta(seq = sequence++, text = chunk))
            }
            add(AiChatTurnCompleted(seq = sequence))
        }

        return Flux.fromIterable(events).concatMap { event ->
            val delay = if (event is AiChatToolCompleted) TOOL_EXECUTION_DELAY else STREAM_DELAY
            Mono.just(event).delayElement(delay)
        }
    }

    private fun responseChunks(content: String): List<String> = listOf(
        "## Spending snapshot\n\n",
        "You asked: **$content**\n\n",
        "Here is an example of how an AI-generated answer could present your results:\n\n",
        "| Category | Amount | Share |\n| --- | ---: | ---: |\n",
        "| Groceries | ${'$'}428.30 | 42% |\n",
        "| Transport | ${'$'}186.75 | 18% |\n",
        "| Dining out | ${'$'}142.10 | 14% |\n\n",
        "- **Groceries** were the largest expense category.\n",
        "- Dining out was lower than groceries by `${'$'}286.20`.\n",
        "- The remaining categories accounted for 26% of the sample total.\n\n",
        "> This is placeholder data from the Chat preview. It is not calculated from your Renalo records yet.",
    )

    companion object {
        private const val TOOL_ACTIVITY_ID = "activity-1"
        private val STREAM_DELAY = Duration.ofMillis(50)
        private val TITLE_GENERATION_DELAY = Duration.ofMillis(300)
        private val TOOL_EXECUTION_DELAY = Duration.ofSeconds(1)
        private val HISTORY_LOADING_DELAY = Duration.ofSeconds(1)
    }
}
