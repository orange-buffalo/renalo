package io.orangebuffalo.renalo.ai

import dev.langchain4j.http.client.HttpClient
import dev.langchain4j.http.client.HttpClientBuilder
import dev.langchain4j.http.client.HttpRequest
import dev.langchain4j.http.client.SuccessfulHttpResponse
import dev.langchain4j.http.client.jdk.JdkHttpClient
import dev.langchain4j.http.client.sse.ServerSentEventListener
import dev.langchain4j.http.client.sse.ServerSentEventParser
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Requires
import jakarta.inject.Named
import jakarta.inject.Singleton
import java.net.http.HttpClient.Version.HTTP_1_1
import java.time.Duration

@Factory
@Requires(property = "renalo.ai-chat.enabled", value = "true")
@Requires(property = "renalo.ai-chat.litellm.base-url", notEquals = "")
class AiChatModelFactory {
    @Singleton
    @Named(CHAT_MODEL_NAME)
    fun chatModel(configuration: AiChatLiteLlmConfiguration): StreamingChatModel =
        baseModel(configuration, CHAT_READ_TIMEOUT)
            .store(true)
            .strictTools(true)
            .parallelToolCalls(false)
            .build()

    @Singleton
    @Named(TITLE_MODEL_NAME)
    fun titleModel(configuration: AiChatLiteLlmConfiguration): StreamingChatModel =
        baseModel(configuration, TITLE_READ_TIMEOUT)
            .store(false)
            .maxOutputTokens(50)
            .build()

    private fun baseModel(configuration: AiChatLiteLlmConfiguration, readTimeout: Duration) =
        OpenAiResponsesStreamingChatModel.builder()
            .baseUrl(configuration.baseUrl.required("base URL").removeSuffix("/"))
            .apiKey(configuration.apiKey.required("API key"))
            .modelName(configuration.model.required("model"))
            .httpClientBuilder(
                LiteLlmResponsesStreamingHttpClientBuilder(
                    JdkHttpClient.builder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .readTimeout(readTimeout)
                        .httpClientBuilder(
                            java.net.http.HttpClient.newBuilder()
                                .version(HTTP_1_1),
                        ),
                ),
            )

    private fun String.required(name: String): String = trim().takeIf(String::isNotEmpty)
        ?: error("AI chat LiteLLM $name must be configured")

    companion object {
        const val CHAT_MODEL_NAME = "aiChatModel"
        const val TITLE_MODEL_NAME = "aiChatTitleModel"
        private val CONNECT_TIMEOUT = Duration.ofSeconds(5)
        private val CHAT_READ_TIMEOUT = Duration.ofSeconds(90)
        private val TITLE_READ_TIMEOUT = Duration.ofSeconds(20)
    }
}

private class LiteLlmResponsesStreamingHttpClientBuilder(
    private val delegate: HttpClientBuilder,
) : HttpClientBuilder {
    override fun connectTimeout(): Duration = delegate.connectTimeout()

    override fun connectTimeout(timeout: Duration): HttpClientBuilder = apply {
        delegate.connectTimeout(timeout)
    }

    override fun readTimeout(): Duration = delegate.readTimeout()

    override fun readTimeout(timeout: Duration): HttpClientBuilder = apply {
        delegate.readTimeout(timeout)
    }

    override fun build(): HttpClient = LiteLlmResponsesStreamingHttpClient(delegate.build())
}

private class LiteLlmResponsesStreamingHttpClient(
    private val delegate: HttpClient,
) : HttpClient {
    override fun execute(request: HttpRequest): SuccessfulHttpResponse = delegate.execute(request)

    override fun execute(
        request: HttpRequest,
        parser: ServerSentEventParser,
        listener: ServerSentEventListener,
    ) {
        delegate.execute(request.withLiteLlmChatGptStreamingCompatibility(), parser, listener)
    }

    private fun HttpRequest.withLiteLlmChatGptStreamingCompatibility(): HttpRequest {
        val compatibleBody = body().replace(STREAMING_FLAG, "\"stream\":false")
        check(compatibleBody != body()) { "Expected a streaming Responses request body" }
        return HttpRequest.builder()
            .method(method())
            .url(url())
            .headers(headers())
            .formDataFields(formDataFields())
            .formDataFiles(formDataFiles())
            .body(compatibleBody)
            .build()
    }

    companion object {
        private val STREAMING_FLAG = Regex("\"stream\"\\s*:\\s*true")
    }
}
