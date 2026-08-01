package io.orangebuffalo.renalo.ai

import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

@JdbcRepository(dialect = Dialect.POSTGRES)
interface AiChatConversationRepository : CrudRepository<AiChatConversation, Long> {
    @Query(
        """
            SELECT *
            FROM ai_chat_conversations
            WHERE user_id = :userId
            ORDER BY updated_at DESC, id DESC
        """,
    )
    fun findByUserIdOrderByUpdatedAtDesc(userId: Long): List<AiChatConversation>

    fun findByIdAndUserId(id: Long, userId: Long): AiChatConversation?
}
