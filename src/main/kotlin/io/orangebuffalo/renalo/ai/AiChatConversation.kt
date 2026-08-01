package io.orangebuffalo.renalo.ai

import io.micronaut.data.annotation.DateCreated
import io.micronaut.data.annotation.DateUpdated
import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import io.micronaut.data.annotation.Version
import java.time.Instant

@MappedEntity("ai_chat_conversations")
data class AiChatConversation(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    val userId: Long,
    val title: String,
    val modelAlias: String? = null,
    @field:DateCreated
    val createdAt: Instant? = null,
    @field:DateUpdated
    val updatedAt: Instant? = null,
    @field:Version
    val version: Long? = null,
)
