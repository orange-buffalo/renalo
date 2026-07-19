package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.math.BigInteger
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

@Singleton
open class TransactionDefaultCurrencyService(
    private val transactionRepository: TransactionRepository,
    private val trackingAccountRepository: TrackingAccountRepository,
    private val fundsTransferRepository: FundsTransferRepository,
) {
    @Transactional
    open fun recalculateForUser(userId: Long) {
        val transactions = transactionRepository.findByUserIdOrderByDateAsc(userId)
        if (transactions.isEmpty()) {
            return
        }

        val accounts = trackingAccountRepository.findByUserIdOrderByName(userId).associateBy { it.id!! }
        val defaultAccount = trackingAccountRepository.findByUserIdAndIsDefaultTrue(userId)
            ?: error("User $userId has transactions but no default tracking account")
        val transfers = fundsTransferRepository.findByUserIdOrderByDateDesc(userId)

        transactions.forEach { transaction ->
            val transactionAccount = accounts[transaction.trackingAccountId]
                ?: error("Transaction ${transaction.id} references a missing tracking account")
            val valuation = if (transactionAccount.currency == defaultAccount.currency) {
                DefaultCurrencyValuation(
                    amountMinor = transaction.amountMinor,
                    source = DefaultCurrencyConversionSource.SAME_CURRENCY,
                )
            } else {
                findTransferEvidence(transaction, transactionAccount, defaultAccount.currency, accounts, transfers)
                    ?.let { evidence ->
                        DefaultCurrencyValuation(
                            amountMinor = convertUsingTransfer(transaction.amountMinor, evidence),
                            source = DefaultCurrencyConversionSource.ACTUAL_TRANSFER,
                            transferId = evidence.transfer.id,
                        )
                    }
                    ?: DefaultCurrencyValuation(source = DefaultCurrencyConversionSource.UNAVAILABLE)
            }

            transactionRepository.update(
                transaction.copy(
                    defaultCurrencyAmountMinor = valuation.amountMinor,
                    defaultCurrency = defaultAccount.currency,
                    defaultCurrencyConversionSource = valuation.source,
                    defaultCurrencyConversionTransferId = valuation.transferId,
                ),
            )
        }
    }

    private fun findTransferEvidence(
        transaction: Transaction,
        transactionAccount: TrackingAccount,
        defaultCurrency: String,
        accounts: Map<Long, TrackingAccount>,
        transfers: List<FundsTransfer>,
    ): TransferEvidence? = transfers.mapNotNull { transfer ->
        val sourceAccount = accounts[transfer.sourceAccountId] ?: return@mapNotNull null
        val targetAccount = accounts[transfer.targetAccountId] ?: return@mapNotNull null
        when {
            sourceAccount.currency == transactionAccount.currency && targetAccount.currency == defaultCurrency ->
                TransferEvidence(
                    transfer = transfer,
                    foreignAccountId = sourceAccount.id!!,
                    foreignAmountMinor = transfer.sourceAmountMinor,
                    defaultAmountMinor = transfer.targetAmountMinor,
                    direction = ConversionDirection.FOREIGN_TO_DEFAULT,
                )

            sourceAccount.currency == defaultCurrency && targetAccount.currency == transactionAccount.currency ->
                TransferEvidence(
                    transfer = transfer,
                    foreignAccountId = targetAccount.id!!,
                    foreignAmountMinor = transfer.targetAmountMinor,
                    defaultAmountMinor = transfer.sourceAmountMinor,
                    direction = ConversionDirection.DEFAULT_TO_FOREIGN,
                )

            else -> null
        }
    }.minWithOrNull(
        compareBy<TransferEvidence> { it.flowRank(transaction) }
            .thenBy { if (it.foreignAccountId == transactionAccount.id) 0 else 1 }
            .thenBy { if (it.foreignAmountMinor == transaction.amountMinor) 0 else 1 }
            .thenBy { ChronoUnit.DAYS.between(transaction.date, it.transfer.date).absoluteValue }
            .thenBy { it.transfer.id },
    )

    private fun TransferEvidence.flowRank(transaction: Transaction): Int {
        val expectedDirection = when (transaction.type) {
            TransactionType.INCOME -> ConversionDirection.FOREIGN_TO_DEFAULT
            TransactionType.EXPENSE -> ConversionDirection.DEFAULT_TO_FOREIGN
        }
        if (direction != expectedDirection) {
            return 2
        }
        val isOnExpectedDateSide = when (transaction.type) {
            TransactionType.INCOME -> !transfer.date.isBefore(transaction.date)
            TransactionType.EXPENSE -> !transfer.date.isAfter(transaction.date)
        }
        return if (isOnExpectedDateSide) 0 else 1
    }

    private fun convertUsingTransfer(amountMinor: Long, evidence: TransferEvidence): Long {
        val numerator = BigInteger.valueOf(amountMinor).multiply(BigInteger.valueOf(evidence.defaultAmountMinor))
        val denominator = BigInteger.valueOf(evidence.foreignAmountMinor)
        val (quotient, remainder) = numerator.divideAndRemainder(denominator)
        val rounded = if (remainder.multiply(BigInteger.TWO) >= denominator) quotient + BigInteger.ONE else quotient
        return rounded.longValueExact()
    }
}

private data class DefaultCurrencyValuation(
    val amountMinor: Long? = null,
    val source: DefaultCurrencyConversionSource,
    val transferId: Long? = null,
)

private data class TransferEvidence(
    val transfer: FundsTransfer,
    val foreignAccountId: Long,
    val foreignAmountMinor: Long,
    val defaultAmountMinor: Long,
    val direction: ConversionDirection,
)

private enum class ConversionDirection {
    FOREIGN_TO_DEFAULT,
    DEFAULT_TO_FOREIGN,
}
