package io.orangebuffalo.renalo.test;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.orangebuffalo.renalo.ai.AiChatTitleGenerator;
import io.orangebuffalo.renalo.ai.AiChatConversationHistoryClient;
import io.orangebuffalo.renalo.ai.AiChatHistoryMessageResponse;
import io.orangebuffalo.renalo.ai.AiChatHistoryMessageRole;
import io.orangebuffalo.renalo.ai.LangChain4jAiChatTitleGenerator;
import jakarta.inject.Singleton;

import java.util.Locale;
import java.util.List;

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
}
