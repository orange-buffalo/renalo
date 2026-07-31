package io.orangebuffalo.renalo.ai

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("renalo.ai-chat")
class AiChatConfiguration {
    var enabled: Boolean = false
}
