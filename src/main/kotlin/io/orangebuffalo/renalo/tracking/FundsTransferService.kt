package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.time.LocalDate

@Singleton
open class FundsTransferService(
    private val fundsTransferRepository: FundsTransferRepository,
    private val trackingAccountRepository: TrackingAccountRepository,
) {
    fun listTransfers(userId: Long, filter: FundsTransferDateFilter = FundsTransferDateFilter()): List<FundsTransferDetails> =
        findTransfers(userId, filter)
            .mapNotNull { it.toDetails(userId) }

    fun findTransfer(userId: Long, transferId: Long): FundsTransferDetails? =
        fundsTransferRepository.findByIdAndUserId(transferId, userId)?.toDetails(userId)

    @Transactional
    open fun createTransfer(userId: Long, request: SaveFundsTransferRequest): SaveFundsTransferResult {
        val transfer = buildTransfer(userId, request) ?: return SaveFundsTransferResult.BadRequest
        return SaveFundsTransferResult.Saved(fundsTransferRepository.save(transfer).toDetails(userId) ?: return SaveFundsTransferResult.BadRequest)
    }

    @Transactional
    open fun updateTransfer(userId: Long, transferId: Long, request: SaveFundsTransferRequest): SaveFundsTransferResult {
        val existingTransfer = fundsTransferRepository.findByIdAndUserId(transferId, userId)
            ?: return SaveFundsTransferResult.NotFound
        val transfer = buildTransfer(userId, request)?.copy(id = existingTransfer.id)
            ?: return SaveFundsTransferResult.BadRequest
        return SaveFundsTransferResult.Saved(fundsTransferRepository.update(transfer).toDetails(userId) ?: return SaveFundsTransferResult.BadRequest)
    }

    @Transactional
    open fun deleteTransfer(userId: Long, transferId: Long): DeleteFundsTransferResult {
        val transfer = fundsTransferRepository.findByIdAndUserId(transferId, userId)
            ?: return DeleteFundsTransferResult.NotFound
        fundsTransferRepository.delete(transfer)
        return DeleteFundsTransferResult.Deleted
    }

    private fun buildTransfer(userId: Long, request: SaveFundsTransferRequest): FundsTransfer? {
        if (request.sourceAccountId == request.targetAccountId || request.sourceAmountMinor <= 0) {
            return null
        }
        val sourceAccount = trackingAccountRepository.findByIdAndUserId(request.sourceAccountId, userId)
            ?: return null
        val targetAccount = trackingAccountRepository.findByIdAndUserId(request.targetAccountId, userId)
            ?: return null
        val targetAmountMinor = if (sourceAccount.currency == targetAccount.currency) {
            request.sourceAmountMinor
        } else {
            request.targetAmountMinor?.takeIf { it > 0 } ?: return null
        }

        return FundsTransfer(
            userId = userId,
            sourceAccountId = sourceAccount.id!!,
            targetAccountId = targetAccount.id!!,
            sourceAmountMinor = request.sourceAmountMinor,
            targetAmountMinor = targetAmountMinor,
            date = request.date,
        )
    }

    private fun findTransfers(userId: Long, filter: FundsTransferDateFilter): List<FundsTransfer> =
        if (filter.from != null && filter.to != null) {
            fundsTransferRepository.findByUserIdAndDateBetweenOrderByDateDesc(userId, filter.from, filter.to)
        } else {
            fundsTransferRepository.findByUserIdOrderByDateDesc(userId)
        }

    private fun FundsTransfer.toDetails(userId: Long): FundsTransferDetails? {
        val sourceAccount = trackingAccountRepository.findByIdAndUserId(sourceAccountId, userId) ?: return null
        val targetAccount = trackingAccountRepository.findByIdAndUserId(targetAccountId, userId) ?: return null
        return FundsTransferDetails(this, sourceAccount, targetAccount)
    }
}

data class FundsTransferDateFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

data class FundsTransferDetails(
    val transfer: FundsTransfer,
    val sourceAccount: TrackingAccount,
    val targetAccount: TrackingAccount,
)

data class SaveFundsTransferRequest(
    val sourceAccountId: Long,
    val targetAccountId: Long,
    val sourceAmountMinor: Long,
    val targetAmountMinor: Long? = null,
    val date: LocalDate,
)

sealed interface SaveFundsTransferResult {
    data class Saved(val transfer: FundsTransferDetails) : SaveFundsTransferResult
    data object BadRequest : SaveFundsTransferResult
    data object NotFound : SaveFundsTransferResult
}

sealed interface DeleteFundsTransferResult {
    data object Deleted : DeleteFundsTransferResult
    data object NotFound : DeleteFundsTransferResult
}
