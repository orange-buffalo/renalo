package io.orangebuffalo.renalo.ai

import dev.langchain4j.http.client.jdk.JdkHttpClient
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.openai.OpenAiResponsesChatModel
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Requires
import jakarta.inject.Named
import jakarta.inject.Singleton
import java.time.Duration

@Factory
@Requires(property = "renalo.ai-chat.enabled", value = "true")
@Requires(property = "renalo.ai-chat.litellm.base-url", notEquals = "")
class AiChatModelFactory {
    @Singleton
    @Named(TITLE_MODEL_NAME)
    fun titleModel(configuration: AiChatLiteLlmConfiguration): ChatModel =
        OpenAiResponsesChatModel.builder()
            .baseUrl(configuration.baseUrl.required("base URL").removeSuffix("/"))
            .apiKey(configuration.apiKey.required("API key"))
            .modelName(configuration.model.required("model"))
            .httpClientBuilder(
                JdkHttpClient.builder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .readTimeout(READ_TIMEOUT),
            )
            .store(false)
            .maxOutputTokens(50)
            .build()

    private fun String.required(name: String): String = trim().takeIf(String::isNotEmpty)
        ?: error("AI chat LiteLLM $name must be configured")

    companion object {
        const val TITLE_MODEL_NAME = "aiChatTitleModel"
        private val CONNECT_TIMEOUT = Duration.ofSeconds(5)
        private val READ_TIMEOUT = Duration.ofSeconds(20)
    }
}
