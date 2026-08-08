package io.orangebuffalo.renalo.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.orangebuffalo.renalo.ai.AiChatTitleGenerator;
import io.orangebuffalo.renalo.ai.AiChatModelGateway;
import io.orangebuffalo.renalo.ai.AiChatModelInput;
import io.orangebuffalo.renalo.ai.AiChatModelStepEvent;
import io.orangebuffalo.renalo.ai.AiChatModelTokenUsage;
import io.orangebuffalo.renalo.ai.AiChatModelToolCall;
import io.orangebuffalo.renalo.ai.LangChain4jAiChatTitleGenerator;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;

import java.util.Locale;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Factory
class TestAiChatTitleGeneratorFactory {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AiChatModelTokenUsage TOKEN_USAGE = new AiChatModelTokenUsage(100L, 20L, 120L);

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
    AiChatModelGateway chatModelGateway() {
        var responseSequence = new AtomicLong();
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
                if (prompt.toLowerCase(Locale.ROOT).contains("continue interrupted")) {
                    requireCompleteFunctionCalls(request.getConversationItems());
                }
                if (prompt.toLowerCase(Locale.ROOT).contains("change topic")
                        && request.getToolSpecifications().stream()
                        .anyMatch(tool -> tool.name().equals("recommend_new_chat"))) {
                    var callId = "call_topic_change_" + responseId;
                    return Flux.just(new AiChatModelStepEvent.Completed(
                            responseId,
                            "renalo-chat",
                            List.of(new AiChatModelToolCall(callId, "recommend_new_chat", "{}")),
                            List.of(
                                    "{\"type\":\"function_call\",\"id\":\"fc_topic_change_" + responseId + "\",\"call_id\":\"" + callId + "\",\"name\":\"recommend_new_chat\",\"arguments\":\"{}\",\"status\":\"completed\"}"
                            ),
                            TOKEN_USAGE
                    ));
                }
                var documentationChartPrompt = prompt.equalsIgnoreCase("Show a chart of spending this month");
                var categoryTotalsArguments = documentationChartPrompt
                        ? "{\"type\":\"EXPENSE\",\"from\":\"2026-08-01\",\"to\":\"2026-08-07\"}"
                        : "{\"type\":\"EXPENSE\",\"from\":\"2026-08-01\",\"to\":\"2026-08-01\"}";
                var serializedCategoryTotalsArguments = categoryTotalsArguments.replace("\"", "\\\"");
                return Flux.just(new AiChatModelStepEvent.Completed(
                        responseId,
                        "renalo-chat",
                        List.of(new AiChatModelToolCall(
                                "call_category_totals",
                                "get_category_totals",
                                categoryTotalsArguments
                        )),
                        List.of(
                                "{\"type\":\"function_call\",\"id\":\"fc_category_totals\",\"call_id\":\"call_category_totals\",\"name\":\"get_category_totals\",\"arguments\":\"" + serializedCategoryTotalsArguments + "\",\"status\":\"completed\"}"
                        ),
                        TOKEN_USAGE
                ));
            }
            var prompt = request.getConversationItems().stream()
                    .map(TestAiChatTitleGeneratorFactory::parseJson)
                    .filter(item -> item.path("type").asText().equals("message"))
                    .filter(item -> item.path("role").asText().equals("user"))
                    .map(item -> item.path("content").path(0).path("text").asText())
                    .reduce((first, second) -> second)
                    .orElse("your request");
            if (prompt.toLowerCase(Locale.ROOT).contains("slow review")) {
                try {
                    Thread.sleep(750);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while simulating result review", interruptedException);
                }
            }
            if (prompt.toLowerCase(Locale.ROOT).contains("chart")
                    && !currentTurnContainsFunctionCall(request.getConversationItems(), "present_chart")) {
                var points = prompt.equalsIgnoreCase("Show a chart of spending this month")
                        ? "[{\"x\":\"Tour travel\",\"y\":\"12900\"},{\"x\":\"Studio hire\",\"y\":\"8500\"},{\"x\":\"Promotion\",\"y\":\"4500\"},{\"x\":\"Guitar gear\",\"y\":\"2899\"},{\"x\":\"Insurance\",\"y\":\"2700\"},{\"x\":\"Web hosting\",\"y\":\"1900\"}]"
                        : "[{\"x\":\"Groceries\",\"y\":\"2345\"}]";
                var arguments = "{\"kind\":\"DONUT\",\"title\":\"Expenses by category\",\"xAxisLabel\":\"Category\",\"xAxisType\":\"CATEGORY\",\"yAxisLabel\":\"Expenses\",\"yAxisType\":\"MONEY_MINOR\",\"currency\":\"AUD\",\"stacked\":false,\"orientation\":\"VERTICAL\",\"series\":[{\"name\":\"Expenses\",\"points\":" + points + "}]}";
                var serializedArguments = arguments.replace("\\", "\\\\").replace("\"", "\\\"");
                return Flux.just(new AiChatModelStepEvent.Completed(
                        responseId,
                        "renalo-chat",
                        List.of(new AiChatModelToolCall("call_chart", "present_chart", arguments)),
                        List.of(
                                "{\"type\":\"function_call\",\"id\":\"fc_chart\",\"call_id\":\"call_chart\",\"name\":\"present_chart\",\"arguments\":\"" + serializedArguments + "\",\"status\":\"completed\"}"
                        ),
                        TOKEN_USAGE
                ));
            }
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
                    new AiChatModelStepEvent.Completed(
                            responseId,
                            "renalo-chat",
                            List.of(),
                             List.of(
                                     "{\"type\":\"message\",\"id\":\"msg_" + responseId + "\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"## Spending snapshot\\n\\nYou asked: **" + prompt.replace("\\", "\\\\").replace("\"", "\\\"") + "**\\n\\nHere is an example of how an AI-generated answer could present your results:\\n\\n| Category | Amount | Share |\\n| --- | ---: | ---: |\\n| Groceries | $428.30 | 42% |\\n| Transport | $186.75 | 18% |\\n| Dining out | $142.10 | 14% |\\n\\n- **Groceries** were the largest expense category.\\n- Dining out was lower than groceries by `$286.20`.\\n- The remaining categories accounted for 26% of the sample total.\\n\\n> This response was generated from Renalo's read-only financial tools.\"}]}"
                             ),
                             TOKEN_USAGE
                     )
            ));
        };
    }

    private static JsonNode parseJson(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid AI chat test event", exception);
        }
    }

    private static boolean currentTurnContainsFunctionCall(List<String> items, String functionName) {
        for (var index = items.size() - 1; index >= 0; index--) {
            var item = parseJson(items.get(index));
            if (item.path("type").asText().equals("message") && item.path("role").asText().equals("user")) {
                return false;
            }
            if (item.path("type").asText().equals("function_call")
                    && item.path("name").asText().equals(functionName)) {
                return true;
            }
        }
        return false;
    }

    private static void requireCompleteFunctionCalls(List<String> items) {
        var pendingCallIds = new java.util.LinkedHashSet<String>();
        for (var itemJson : items) {
            var item = parseJson(itemJson);
            if (item.path("type").asText().equals("function_call")) {
                pendingCallIds.add(item.path("call_id").asText());
            } else if (item.path("type").asText().equals("function_call_output")) {
                pendingCallIds.remove(item.path("call_id").asText());
            }
        }
        if (!pendingCallIds.isEmpty()) {
            throw new IllegalStateException("Test model received function calls without outputs: " + pendingCallIds);
        }
    }
}
