package io.orangebuffalo.renalo.ai

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("renalo.ai-chat")
class AiChatConfiguration {
    var enabled: Boolean = false
}

@ConfigurationProperties("renalo.ai-chat.litellm")
class AiChatLiteLlmConfiguration {
    var baseUrl: String = ""
    var apiKey: String = ""
    var model: String = ""
    var maxContextTokens: Long = 0
}
