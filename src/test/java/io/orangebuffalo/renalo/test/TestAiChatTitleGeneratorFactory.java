package io.orangebuffalo.renalo.test;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.orangebuffalo.renalo.ai.AiChatTitleGenerator;
import io.orangebuffalo.renalo.ai.AiChatConversationHistoryClient;
import io.orangebuffalo.renalo.ai.AiChatHistoryMessageResponse;
import io.orangebuffalo.renalo.ai.AiChatHistoryMessageRole;
import io.orangebuffalo.renalo.ai.AiChatModelGateway;
import io.orangebuffalo.renalo.ai.AiChatModelInput;
import io.orangebuffalo.renalo.ai.AiChatModelStepEvent;
import io.orangebuffalo.renalo.ai.AiChatModelToolCall;
import io.orangebuffalo.renalo.ai.LangChain4jAiChatTitleGenerator;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;

import java.util.Locale;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

@Factory
class TestAiChatTitleGeneratorFactory {
    @Singleton
    @Replaces(LangChain4jAiChatTitleGenerator.class)
    AiChatTitleGenerator titleGenerator() {
        return firstPrompt -> {
            var normalizedPrompt = firstPrompt.toLowerCase(Locale.ROOT);
            if (normalizedPrompt.contains("slow title")) {
                try {
                    Thread.sleep(750);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while simulating title generation", interruptedException);
                }
            }
            if (normalizedPrompt.contains("fail title")) {
                throw new IllegalStateException("Simulated title generation failure");
            }
            if (normalizedPrompt.contains("month")) {
                return "Monthly spending review";
            }
            if (normalizedPrompt.contains("spend")) {
                return "Spending review";
            }
            return "Financial overview";
        };
    }

    @Singleton
    AiChatConversationHistoryClient conversationHistoryClient() {
        return latestResponseId -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while simulating history lookup", interruptedException);
            }
            if (latestResponseId.contains("missing")) {
                throw new IllegalStateException("Simulated missing LiteLLM response");
            }
            return List.of(
                    new AiChatHistoryMessageResponse(
                            AiChatHistoryMessageRole.USER,
                            "What did we discuss in this chat?"
                    ),
                    new AiChatHistoryMessageResponse(
                            AiChatHistoryMessageRole.ASSISTANT,
                            "## Saved conversation\n\nThis history was loaded from LiteLLM."
                    )
            );
        };
    }

    @Singleton
    AiChatModelGateway chatModelGateway() {
        var responseSequence = new AtomicLong();
        var promptsByResponseId = new ConcurrentHashMap<String, String>();
        return request -> {
            var responseId = "resp_test_" + responseSequence.incrementAndGet();
            if (request.getInput().stream().anyMatch(AiChatModelInput.User.class::isInstance)) {
                var prompt = request.getInput().stream()
                        .filter(AiChatModelInput.User.class::isInstance)
                        .map(AiChatModelInput.User.class::cast)
                        .findFirst()
                        .orElseThrow()
                        .getContent();
                if (prompt.toLowerCase(Locale.ROOT).contains("fail model")) {
                    return Flux.error(new IllegalStateException("Simulated model failure"));
                }
                promptsByResponseId.put(responseId, prompt);
                return Flux.just(new AiChatModelStepEvent.Completed(
                        responseId,
                        "renalo-chat",
                        List.of(new AiChatModelToolCall(
                                "call_category_totals",
                                "get_category_totals",
                                "{\"type\":\"EXPENSE\",\"from\":\"2026-08-01\",\"to\":\"2026-08-01\"}"
                        ))
                ));
            }
            var prompt = promptsByResponseId.getOrDefault(request.getPreviousResponseId(), "your request");
            return Flux.fromIterable(List.of(
                    new AiChatModelStepEvent.TextDelta("## Spending snapshot\n\n"),
                    new AiChatModelStepEvent.TextDelta("You asked: **" + prompt + "**\n\n"),
                    new AiChatModelStepEvent.TextDelta("Here is an example of how an AI-generated answer could present your results:\n\n"),
                    new AiChatModelStepEvent.TextDelta("| Category | Amount | Share |\n| --- | ---: | ---: |\n"),
                    new AiChatModelStepEvent.TextDelta("| Groceries | $428.30 | 42% |\n"),
                    new AiChatModelStepEvent.TextDelta("| Transport | $186.75 | 18% |\n"),
                    new AiChatModelStepEvent.TextDelta("| Dining out | $142.10 | 14% |\n\n"),
                    new AiChatModelStepEvent.TextDelta("- **Groceries** were the largest expense category.\n"),
                    new AiChatModelStepEvent.TextDelta("- Dining out was lower than groceries by `$286.20`.\n"),
                    new AiChatModelStepEvent.TextDelta("- The remaining categories accounted for 26% of the sample total.\n\n"),
                    new AiChatModelStepEvent.TextDelta("> This response was generated from Renalo's read-only financial tools."),
                    new AiChatModelStepEvent.Completed(responseId, "renalo-chat", List.of())
            ));
        };
    }
}
