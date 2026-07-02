package io.orangebuffalo.renalo.tracking

import io.orangebuffalo.renalo.time.TimeProvider
import jakarta.inject.Singleton
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@Singleton
class DashboardService(
    private val trackingAccountRepository: TrackingAccountRepository,
    private val transactionRepository: TransactionRepository,
    private val fundsTransferRepository: FundsTransferRepository,
    private val timeProvider: TimeProvider,
) {
    fun getAccountSummaries(userId: Long): List<AccountDashboardSummary> {
        val accounts = trackingAccountRepository.findByUserIdOrderByName(userId)
        if (accounts.isEmpty()) {
            return emptyList()
        }

        val today = LocalDate.ofInstant(timeProvider.now(), ZoneOffset.UTC)
        val currentMonth = YearMonth.from(today)
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
                    if (!transaction.date.isAfter(today)) {
                        totalBalanceMinor += transaction.amountMinor
                    }
                    if (transaction.date.isIn(currentMonth) && !transaction.date.isAfter(today)) {
                        currentMonthInflowMinor += transaction.amountMinor
                    }
                }
            }

        transactionRepository.findByUserIdAndTypeOrderByDateDesc(userId, TransactionType.EXPENSE)
            .forEach { transaction ->
                summaries[transaction.trackingAccountId]?.apply {
                    if (!transaction.date.isAfter(today)) {
                        totalBalanceMinor -= transaction.amountMinor
                    }
                    if (transaction.date.isIn(currentMonth) && !transaction.date.isAfter(today)) {
                        currentMonthOutflowMinor += transaction.amountMinor
                    }
                }
            }

        fundsTransferRepository.findByUserIdOrderByDateDesc(userId)
            .forEach { transfer ->
                summaries[transfer.sourceAccountId]?.apply {
                    if (!transfer.date.isAfter(today)) {
                        totalBalanceMinor -= transfer.sourceAmountMinor
                    }
                    if (transfer.date.isIn(currentMonth) && !transfer.date.isAfter(today)) {
                        currentMonthOutflowMinor += transfer.sourceAmountMinor
                    }
                }
                summaries[transfer.targetAccountId]?.apply {
                    if (!transfer.date.isAfter(today)) {
                        totalBalanceMinor += transfer.targetAmountMinor
                    }
                    if (transfer.date.isIn(currentMonth) && !transfer.date.isAfter(today)) {
                        currentMonthInflowMinor += transfer.targetAmountMinor
                    }
                }
            }

        return summaries.values.map { it.toResponse() }
    }
}

private fun LocalDate.isIn(month: YearMonth): Boolean = YearMonth.from(this) == month

private data class MutableAccountDashboardSummary(
    val account: TrackingAccount,
    var totalBalanceMinor: Long,
    var currentMonthInflowMinor: Long = 0,
    var currentMonthOutflowMinor: Long = 0,
) {
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
