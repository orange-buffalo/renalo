package io.orangebuffalo.renalo.tracking

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.transaction.annotation.Transactional
import io.orangebuffalo.renalo.time.TimeProvider
import jakarta.inject.Singleton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Singleton
open class AccountAdjustmentService(
    private val accountAdjustmentRepository: AccountAdjustmentRepository,
    private val trackingAccountRepository: TrackingAccountRepository,
    private val transactionRepository: TransactionRepository,
    private val fundsTransferRepository: FundsTransferRepository,
    private val timeProvider: TimeProvider,
) {
    open fun getAdjustmentsWithBalance(userId: Long, trackingAccountId: Long): AccountAdjustmentsData? {
        val account = trackingAccountRepository.findByIdAndUserId(trackingAccountId, userId)
            ?: return null

        val adjustments = accountAdjustmentRepository
            .findByUserIdAndTrackingAccountIdOrderByIdDesc(userId, trackingAccountId)

        val currentBalance = computeBalance(userId, account)
        val adjustmentSum = adjustments.sumOf { it.adjustmentAmountMinor }

        return AccountAdjustmentsData(
            accountId = account.id ?: return null,
            accountName = account.name,
            currency = account.currency,
            currentBalanceMinor = currentBalance + adjustmentSum,
            baseBalanceMinor = currentBalance,
            adjustments = adjustments.map { it.toResponse() },
        )
    }

    @Transactional
    open fun createAdjustment(userId: Long, trackingAccountId: Long, amountMinor: Long): CreateAdjustmentResult {
        if (amountMinor == 0L) {
            return CreateAdjustmentResult.InvalidAmount
        }

        val account = trackingAccountRepository.findByIdAndUserId(trackingAccountId, userId)
            ?: return CreateAdjustmentResult.AccountNotFound

        val adjustment = accountAdjustmentRepository.save(
            AccountAdjustment(
                userId = userId,
                trackingAccountId = account.id ?: return CreateAdjustmentResult.AccountNotFound,
                adjustmentAmountMinor = amountMinor,
                date = LocalDate.ofInstant(timeProvider.now(), ZoneOffset.UTC),
            ),
        )

        return CreateAdjustmentResult.Success(adjustment.toResponse())
    }

    @Transactional
    open fun deleteAdjustment(userId: Long, adjustmentId: Long): DeleteAdjustmentResult {
        val adjustment = accountAdjustmentRepository.findByIdAndUserId(adjustmentId, userId)
            ?: return DeleteAdjustmentResult.NotFound

        accountAdjustmentRepository.delete(adjustment)
        return DeleteAdjustmentResult.Deleted
    }

    private fun computeBalance(userId: Long, account: TrackingAccount): Long {
        var balance = account.initialBalanceMinor

        transactionRepository.findByUserIdAndTypeOrderByDateDesc(userId, TransactionType.INCOME)
            .filter { it.trackingAccountId == account.id }
            .forEach { balance += it.amountMinor }

        transactionRepository.findByUserIdAndTypeOrderByDateDesc(userId, TransactionType.EXPENSE)
            .filter { it.trackingAccountId == account.id }
            .forEach { balance -= it.amountMinor }

        fundsTransferRepository.findByUserIdOrderByDateDesc(userId)
            .forEach { transfer ->
                if (transfer.sourceAccountId == account.id) {
                    balance -= transfer.sourceAmountMinor
                }
                if (transfer.targetAccountId == account.id) {
                    balance += transfer.targetAmountMinor
                }
            }

        return balance
    }

    private fun AccountAdjustment.toResponse() = AccountAdjustmentResponse(
        id = id ?: error("Account adjustment must be persisted before it can be returned"),
        adjustmentAmountMinor = adjustmentAmountMinor,
        date = date,
        createdAt = createdAt ?: error("Account adjustment must have a creation timestamp"),
    )
}

data class AccountAdjustmentsData(
    val accountId: Long,
    val accountName: String,
    val currency: String,
    val currentBalanceMinor: Long,
    val baseBalanceMinor: Long,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val adjustments: List<AccountAdjustmentResponse>,
)

data class AccountAdjustmentResponse(
    val id: Long,
    val adjustmentAmountMinor: Long,
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val date: LocalDate,
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    val createdAt: Instant,
)

data class CreateAdjustmentRequest(
    val adjustmentAmountMinor: Long,
)

sealed interface CreateAdjustmentResult {
    data class Success(val adjustment: AccountAdjustmentResponse) : CreateAdjustmentResult
    data object InvalidAmount : CreateAdjustmentResult
    data object AccountNotFound : CreateAdjustmentResult
}

sealed interface DeleteAdjustmentResult {
    data object Deleted : DeleteAdjustmentResult
    data object NotFound : DeleteAdjustmentResult
}
