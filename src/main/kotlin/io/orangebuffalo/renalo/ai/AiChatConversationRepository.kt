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

    @Query(
        """
            UPDATE ai_chat_conversations
            SET external_response_id = :newExternalResponseId,
                model_alias = COALESCE(model_alias, :modelAlias),
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = :conversationId
              AND user_id = :userId
              AND external_response_id IS NOT DISTINCT FROM :expectedExternalResponseId
              AND (model_alias IS NULL OR model_alias = :modelAlias)
        """,
    )
    fun updateExternalState(
        userId: Long,
        conversationId: Long,
        expectedExternalResponseId: String?,
        newExternalResponseId: String,
        modelAlias: String,
    ): Long
}
