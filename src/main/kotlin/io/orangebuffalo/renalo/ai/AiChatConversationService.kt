package io.orangebuffalo.renalo.ai

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

@Singleton
open class AiChatConversationService(
    private val conversationRepository: AiChatConversationRepository,
) {
    fun listConversations(userId: Long): List<AiChatConversation> =
        conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId)

    fun findConversation(userId: Long, conversationId: Long): AiChatConversation? =
        conversationRepository.findByIdAndUserId(conversationId, userId)

    @Transactional
    open fun prepareConversation(userId: Long, conversationId: Long?): PrepareAiChatConversationResult {
        if (conversationId == null) {
            val conversation = conversationRepository.save(
                AiChatConversation(
                    userId = userId,
                    title = DEFAULT_TITLE,
                ),
            ).reload(userId)
            return PrepareAiChatConversationResult.Prepared(
                conversation = conversation,
                wasCreated = true,
            )
        }

        val conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
            ?: return PrepareAiChatConversationResult.NotFound
        return PrepareAiChatConversationResult.Prepared(
            conversation = conversationRepository.update(conversation.copy()).reload(userId),
            wasCreated = false,
        )
    }

    @Transactional
    open fun renameConversation(userId: Long, conversationId: Long, title: String): SaveAiChatConversationResult {
        val normalizedTitle = title.trim().takeIf { it.isNotEmpty() && it.length <= TITLE_MAX_LENGTH }
            ?: return SaveAiChatConversationResult.BadRequest
        val conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
            ?: return SaveAiChatConversationResult.NotFound
        return SaveAiChatConversationResult.Saved(
            conversationRepository.update(conversation.copy(title = normalizedTitle)).reload(userId),
        )
    }

    @Transactional
    open fun updateGeneratedTitle(userId: Long, conversationId: Long, title: String): AiChatConversation {
        val conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
            ?: error("AI chat conversation disappeared while its title was being generated")
        return conversationRepository.update(conversation.copy(title = title)).reload(userId)
    }

    @Transactional
    open fun completeTurn(userId: Long, conversationId: Long): AiChatConversation {
        val conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
            ?: error("AI chat conversation disappeared while its turn was completing")
        return conversationRepository.update(conversation.copy()).reload(userId)
    }

    @Transactional
    open fun setModelAlias(
        userId: Long,
        conversationId: Long,
        modelAlias: String,
    ): AiChatConversation {
        require(modelAlias.isNotBlank())
        check(
            conversationRepository.setModelAlias(
                userId,
                conversationId,
                modelAlias,
            ) == 1L,
        ) { "AI chat model alias changed concurrently" }
        return conversationRepository.findByIdAndUserId(conversationId, userId)
            ?: error("AI chat conversation disappeared while its model alias was being updated")
    }

    @Transactional
    open fun deleteConversation(userId: Long, conversationId: Long): DeleteAiChatConversationResult {
        val conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
            ?: return DeleteAiChatConversationResult.NotFound
        conversationRepository.delete(conversation)
        return DeleteAiChatConversationResult.Deleted
    }

    private fun AiChatConversation.reload(userId: Long): AiChatConversation =
        conversationRepository.findByIdAndUserId(
            id ?: error("AI chat conversation must be persisted before it can be reloaded"),
            userId,
        ) ?: error("Persisted AI chat conversation could not be reloaded")

    companion object {
        const val TITLE_MAX_LENGTH = 100
        private const val DEFAULT_TITLE = "New chat"
    }
}

sealed interface PrepareAiChatConversationResult {
    data class Prepared(
        val conversation: AiChatConversation,
        val wasCreated: Boolean,
    ) : PrepareAiChatConversationResult

    data object NotFound : PrepareAiChatConversationResult
}

sealed interface SaveAiChatConversationResult {
    data class Saved(val conversation: AiChatConversation) : SaveAiChatConversationResult
    data object NotFound : SaveAiChatConversationResult
    data object BadRequest : SaveAiChatConversationResult
}

enum class DeleteAiChatConversationResult {
    Deleted,
    NotFound,
}
