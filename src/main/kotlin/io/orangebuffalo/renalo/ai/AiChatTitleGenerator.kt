package io.orangebuffalo.renalo.ai

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import io.micronaut.context.annotation.Requires
import jakarta.inject.Named
import jakarta.inject.Singleton

interface AiChatTitleGenerator {
    fun generateTitle(firstPrompt: String): String
}

@Singleton
@Requires(property = "renalo.ai-chat.enabled", value = "true")
@Requires(property = "renalo.ai-chat.litellm.base-url", notEquals = "")
class LangChain4jAiChatTitleGenerator(
    @Named(AiChatModelFactory.TITLE_MODEL_NAME) private val titleModel: ChatModel,
) : AiChatTitleGenerator {
    override fun generateTitle(firstPrompt: String): String {
        val response = titleModel.chat(
            ChatRequest.builder()
                .messages(
                    SystemMessage.from(TITLE_INSTRUCTIONS),
                    UserMessage.from(firstPrompt),
                )
                .build(),
        )
        return response.aiMessage().text()
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.removeSurrounding("\"")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(AiChatConversationService.TITLE_MAX_LENGTH)
            ?: error("AI title model returned no assistant text")
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val TITLE_INSTRUCTIONS = """
            Generate a concise title for a personal-finance assistant conversation based on the user's first message.
            Return only the title as plain text, without Markdown, quotation marks, or ending punctuation.
            Use at most 60 characters and do not answer the user's question.
        """.trimIndent()
    }
}
