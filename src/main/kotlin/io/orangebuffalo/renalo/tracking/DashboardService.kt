package io.orangebuffalo.renalo.tracking

import jakarta.inject.Singleton
import java.time.LocalDate
import java.time.YearMonth

@Singleton
class DashboardService(
    private val trackingAccountRepository: TrackingAccountRepository,
    private val transactionRepository: TransactionRepository,
    private val fundsTransferRepository: FundsTransferRepository,
    private val accountAdjustmentRepository: AccountAdjustmentRepository,
) {
    fun getAccountSummaries(userId: Long, currentDate: LocalDate): List<AccountDashboardSummary> {
        val accounts = trackingAccountRepository.findByUserIdAndArchivedFalseOrderByName(userId)
        if (accounts.isEmpty()) {
            return emptyList()
        }

        val currentMonth = YearMonth.from(currentDate)
        val summaries = accounts.associate { account ->
            val accountId = account.id ?: error("Tracking account must be persisted before dashboard summary can be built")
            accountId to MutableAccountDashboardSummary(
                account = account,
                totalBalanceMinor = account.initialBalanceMinor,
            )
        }

        transactionRepository.findByUserIdAndTypeOrderByDateDesc(userId, TransactionType.INCOME)
            .forEach { transaction ->
                summaries[transaction.trackingAccountId]?.apply {
                    recordActivity(transaction.date)
                    if (!transaction.date.isAfter(currentDate)) {
                        totalBalanceMinor = FinancialMath.add(totalBalanceMinor, transaction.amountMinor)
                    }
                    if (transaction.date.isIn(currentMonth) && !transaction.date.isAfter(currentDate)) {
                        currentMonthInflowMinor = FinancialMath.add(currentMonthInflowMinor, transaction.amountMinor)
                    }
                }
            }

        transactionRepository.findByUserIdAndTypeOrderByDateDesc(userId, TransactionType.EXPENSE)
            .forEach { transaction ->
                summaries[transaction.trackingAccountId]?.apply {
                    recordActivity(transaction.date)
                    if (!transaction.date.isAfter(currentDate)) {
                        totalBalanceMinor = FinancialMath.subtract(totalBalanceMinor, transaction.amountMinor)
                    }
                    if (transaction.date.isIn(currentMonth) && !transaction.date.isAfter(currentDate)) {
                        currentMonthOutflowMinor = FinancialMath.add(currentMonthOutflowMinor, transaction.amountMinor)
                    }
                }
            }

        fundsTransferRepository.findByUserIdOrderByDateDesc(userId)
            .forEach { transfer ->
                summaries[transfer.sourceAccountId]?.apply {
                    recordActivity(transfer.date)
                    if (!transfer.date.isAfter(currentDate)) {
                        totalBalanceMinor = FinancialMath.subtract(totalBalanceMinor, transfer.sourceAmountMinor)
                    }
                    if (transfer.date.isIn(currentMonth) && !transfer.date.isAfter(currentDate)) {
                        currentMonthOutflowMinor = FinancialMath.add(currentMonthOutflowMinor, transfer.sourceAmountMinor)
                    }
                }
                summaries[transfer.targetAccountId]?.apply {
                    recordActivity(transfer.date)
                    if (!transfer.date.isAfter(currentDate)) {
                        totalBalanceMinor = FinancialMath.add(totalBalanceMinor, transfer.targetAmountMinor)
                    }
                    if (transfer.date.isIn(currentMonth) && !transfer.date.isAfter(currentDate)) {
                        currentMonthInflowMinor = FinancialMath.add(currentMonthInflowMinor, transfer.targetAmountMinor)
                    }
                }
            }

        accountAdjustmentRepository.findByUserId(userId)
            .forEach { adjustment ->
                summaries[adjustment.trackingAccountId]?.apply {
                    if (!adjustment.date.isAfter(currentDate)) {
                        totalBalanceMinor = FinancialMath.add(totalBalanceMinor, adjustment.adjustmentAmountMinor)
                    }
                }
            }

        return summaries.values
            .sortedWith(
                compareByDescending<MutableAccountDashboardSummary> { it.lastRecordDate ?: LocalDate.MIN }
                    .thenBy { it.account.name },
            )
            .map { it.toResponse() }
    }
}

private fun LocalDate.isIn(month: YearMonth): Boolean = YearMonth.from(this) == month

private data class MutableAccountDashboardSummary(
    val account: TrackingAccount,
    var totalBalanceMinor: Long,
    var currentMonthInflowMinor: Long = 0,
    var currentMonthOutflowMinor: Long = 0,
    var lastRecordDate: LocalDate? = null,
) {
    fun recordActivity(date: LocalDate) {
        if (lastRecordDate == null || date.isAfter(lastRecordDate)) {
            lastRecordDate = date
        }
    }

    fun toResponse() = AccountDashboardSummary(
        accountId = account.id ?: error("Tracking account must be persisted before dashboard summary can be returned"),
        accountName = account.name,
        currency = account.currency,
        totalBalanceMinor = totalBalanceMinor,
        currentMonthInflowMinor = currentMonthInflowMinor,
        currentMonthOutflowMinor = currentMonthOutflowMinor,
    )
}

data class AccountDashboardSummary(
    val accountId: Long,
    val accountName: String,
    val currency: String,
    val totalBalanceMinor: Long,
    val currentMonthInflowMinor: Long,
    val currentMonthOutflowMinor: Long,
)
