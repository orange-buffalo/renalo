package io.orangebuffalo.renalo.test;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.orangebuffalo.renalo.ai.AiChatTitleGenerator;
import io.orangebuffalo.renalo.ai.LangChain4jAiChatTitleGenerator;
import jakarta.inject.Singleton;

import java.util.Locale;

@Factory
class TestAiChatTitleGeneratorFactory {
    @Singleton
    @Replaces(LangChain4jAiChatTitleGenerator.class)
    AiChatTitleGenerator titleGenerator() {
        return firstPrompt -> {
            var normalizedPrompt = firstPrompt.toLowerCase(Locale.ROOT);
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
}
