package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.util.Currency

@Singleton
open class TrackingAccountService(
    private val trackingAccountRepository: TrackingAccountRepository,
    private val transactionRepository: TransactionRepository,
    private val fundsTransferRepository: FundsTransferRepository,
    private val accountAdjustmentRepository: AccountAdjustmentRepository,
    private val recurringTransactionRuleRepository: RecurringTransactionRuleRepository,
) {
    @Transactional
    open fun createDefaultAccountForUser(userId: Long): TrackingAccount {
        if (trackingAccountRepository.countByUserId(userId) > 0) {
            return trackingAccountRepository.findByUserIdOrderByName(userId).first()
        }

        return trackingAccountRepository.save(
            TrackingAccount(
                userId = userId,
                name = "Main",
                currency = "AUD",
                initialBalanceMinor = 0,
                isDefault = true,
            ),
        )
    }

    fun listAccounts(userId: Long, includeArchived: Boolean): List<TrackingAccount> =
        if (includeArchived) {
            trackingAccountRepository.findByUserIdOrderByName(userId)
        } else {
            trackingAccountRepository.findByUserIdAndArchivedFalseOrderByName(userId)
        }

    fun findAccount(userId: Long, accountId: Long): TrackingAccount? =
        trackingAccountRepository.findByIdAndUserId(accountId, userId)

    fun getMergeSummary(userId: Long, accountId: Long): TrackingAccountMergeSummary? {
        val sourceAccount = trackingAccountRepository.findByIdAndUserId(accountId, userId)
            ?: return null
        val targetAccounts = trackingAccountRepository.findByUserIdOrderByName(userId)
            .filter { it.id != sourceAccount.id && it.currency == sourceAccount.currency }

        return TrackingAccountMergeSummary(
            sourceAccount = sourceAccount,
            expensesCount = transactionRepository.countByUserIdAndTrackingAccountIdAndType(
                userId = userId,
                trackingAccountId = accountId,
                type = TransactionType.EXPENSE,
            ),
            incomesCount = transactionRepository.countByUserIdAndTrackingAccountIdAndType(
                userId = userId,
                trackingAccountId = accountId,
                type = TransactionType.INCOME,
            ),
            transfersCount = fundsTransferRepository.countByUserIdAndAccountId(userId, accountId),
            targetAccounts = targetAccounts,
        )
    }

    @Transactional
    open fun createAccount(userId: Long, request: SaveTrackingAccountRequest): TrackingAccount? {
        val name = request.name.trim()
        val currency = request.currency.trim().uppercase()
        if (name.isBlank() || !isValidCurrency(currency)) {
            return null
        }

        val shouldBeDefault = request.isDefault || trackingAccountRepository.countByUserId(userId) == 0L
        if (shouldBeDefault) {
            trackingAccountRepository.clearDefaultForUser(userId)
        }

        return trackingAccountRepository.save(
            TrackingAccount(
                userId = userId,
                name = name,
                currency = currency,
                initialBalanceMinor = request.initialBalanceMinor,
                isDefault = shouldBeDefault,
                archived = false,
            ),
        )
    }

    @Transactional
    open fun updateAccount(userId: Long, accountId: Long, request: SaveTrackingAccountRequest): TrackingAccount? {
        val account = trackingAccountRepository.findByIdAndUserId(accountId, userId)
            ?: return null
        val name = request.name.trim()
        val currency = request.currency.trim().uppercase()
        if (name.isBlank() || !isValidCurrency(currency)) {
            return null
        }

        val shouldBeDefault = account.isDefault || request.isDefault
        if (request.isDefault && !account.isDefault) {
            trackingAccountRepository.clearDefaultForUser(userId)
        }

        return trackingAccountRepository.update(
            account.copy(
                name = name,
                currency = currency,
                initialBalanceMinor = request.initialBalanceMinor,
                isDefault = shouldBeDefault,
            ),
        )
    }

    @Transactional
    open fun archiveAccount(userId: Long, accountId: Long): TrackingAccount? {
        val account = trackingAccountRepository.findByIdAndUserId(accountId, userId)
            ?: return null

        return trackingAccountRepository.update(account.copy(archived = true))
    }

    @Transactional
    open fun unarchiveAccount(userId: Long, accountId: Long): TrackingAccount? {
        val account = trackingAccountRepository.findByIdAndUserId(accountId, userId)
            ?: return null

        return trackingAccountRepository.update(account.copy(archived = false))
    }

    @Transactional
    open fun mergeAccount(userId: Long, sourceAccountId: Long, request: MergeTrackingAccountRequest): TrackingAccountMergeResult {
        val sourceAccount = trackingAccountRepository.findByIdAndUserId(sourceAccountId, userId)
            ?: return TrackingAccountMergeResult.NOT_FOUND
        val targetAccount = trackingAccountRepository.findByIdAndUserId(request.targetAccountId, userId)
            ?: return TrackingAccountMergeResult.INVALID_TARGET
        if (targetAccount.id == sourceAccount.id || targetAccount.currency != sourceAccount.currency) {
            return TrackingAccountMergeResult.INVALID_TARGET
        }
        val persistedSourceAccountId = sourceAccount.id
            ?: return TrackingAccountMergeResult.NOT_FOUND
        val persistedTargetAccountId = targetAccount.id
            ?: return TrackingAccountMergeResult.INVALID_TARGET

        if (sourceAccount.isDefault) {
            trackingAccountRepository.clearDefaultForUser(userId)
        }

        val internalTransferNet = fundsTransferRepository.findByUserIdOrderByDateDesc(userId)
            .filter {
                (it.sourceAccountId == persistedSourceAccountId && it.targetAccountId == persistedTargetAccountId) ||
                    (it.sourceAccountId == persistedTargetAccountId && it.targetAccountId == persistedSourceAccountId)
            }
            .fold(0L) { total, transfer ->
                FinancialMath.add(total, FinancialMath.subtract(transfer.targetAmountMinor, transfer.sourceAmountMinor))
            }
        val mergedInitialBalance = FinancialMath.add(
            FinancialMath.add(targetAccount.initialBalanceMinor, sourceAccount.initialBalanceMinor),
            internalTransferNet,
        )

        trackingAccountRepository.update(
            targetAccount.copy(
                initialBalanceMinor = mergedInitialBalance,
                isDefault = targetAccount.isDefault || sourceAccount.isDefault,
            ),
        )

        fundsTransferRepository.deleteInternalTransfers(userId, persistedSourceAccountId, persistedTargetAccountId)
        transactionRepository.reassignTrackingAccount(userId, persistedSourceAccountId, persistedTargetAccountId)
        accountAdjustmentRepository.reassignTrackingAccount(userId, persistedSourceAccountId, persistedTargetAccountId)
        recurringTransactionRuleRepository.reassignTrackingAccount(userId, persistedSourceAccountId, persistedTargetAccountId)
        fundsTransferRepository.reassignSourceAccount(userId, persistedSourceAccountId, persistedTargetAccountId)
        fundsTransferRepository.reassignTargetAccount(userId, persistedSourceAccountId, persistedTargetAccountId)
        trackingAccountRepository.deleteByIdAndUserId(persistedSourceAccountId, userId)

        return TrackingAccountMergeResult.MERGED
    }

    private fun isValidCurrency(currency: String): Boolean = try {
        Currency.getInstance(currency)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

data class SaveTrackingAccountRequest(
    val name: String,
    val currency: String,
    val initialBalanceMinor: Long = 0,
    val isDefault: Boolean = false,
)

data class MergeTrackingAccountRequest(
    val targetAccountId: Long,
)

data class TrackingAccountMergeSummary(
    val sourceAccount: TrackingAccount,
    val expensesCount: Long,
    val incomesCount: Long,
    val transfersCount: Long,
    val targetAccounts: List<TrackingAccount>,
)

enum class TrackingAccountMergeResult {
    MERGED,
    NOT_FOUND,
    INVALID_TARGET,
}
