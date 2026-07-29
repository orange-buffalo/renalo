package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

@Singleton
open class NetWorthAnalyticsService(
    private val trackingAccountRepository: TrackingAccountRepository,
    private val transactionRepository: TransactionRepository,
    private val fundsTransferRepository: FundsTransferRepository,
    private val accountAdjustmentRepository: AccountAdjustmentRepository,
) {
    @Transactional(readOnly = true)
    open fun getTimeSeries(
        userId: Long,
        requestedFrom: LocalDate?,
        requestedTo: LocalDate?,
        requestedGranularity: TransactionTimeSeriesGranularity,
        currentDate: LocalDate,
    ): TransactionTimeSeries {
        val to = minOf(requestedTo ?: currentDate, currentDate)
        require(requestedFrom == null || !requestedFrom.isAfter(to))

        val accounts = trackingAccountRepository.findByUserIdOrderByName(userId)
        val defaultCurrency = accounts.singleOrNull { it.isDefault }?.currency
            ?: return TransactionTimeSeries(
                resolveGranularity(requestedGranularity, requestedFrom ?: to, to),
                requestedFrom ?: to,
                to,
                emptyList(),
            )
        val accountsById = accounts.associateBy { it.id!! }
        val transactions = TransactionType.entries
            .flatMap { transactionRepository.findByUserIdAndTypeOrderByDateDesc(userId, it) }
            .filter { !it.date.isAfter(currentDate) }
        val transfers = fundsTransferRepository.findByUserIdOrderByDateDesc(userId)
            .filter { !it.date.isAfter(currentDate) }
        val adjustments = accountAdjustmentRepository.findByUserId(userId)
            .filter { !it.date.isAfter(currentDate) }

        val activityDates = mutableMapOf<Long, LocalDate>()
        transactions.forEach { activityDates.recordEarliest(it.trackingAccountId, it.date) }
        transfers.forEach {
            activityDates.recordEarliest(it.sourceAccountId, it.date)
            activityDates.recordEarliest(it.targetAccountId, it.date)
        }
        adjustments.forEach { activityDates.recordEarliest(it.trackingAccountId, it.date) }

        val ratesByCurrency = transactions.mapNotNull { transaction ->
            val accountCurrency = accountsById[transaction.trackingAccountId]?.currency ?: return@mapNotNull null
            val defaultAmount = transaction.defaultCurrencyAmountMinor ?: return@mapNotNull null
            if (accountCurrency == defaultCurrency || transaction.defaultCurrency != defaultCurrency) {
                return@mapNotNull null
            }
            CurrencyRate(
                currency = accountCurrency,
                date = transaction.date,
                transactionId = transaction.id!!,
                foreignAmountMinor = transaction.amountMinor,
                defaultAmountMinor = defaultAmount,
            )
        }.groupBy { it.currency }

        var openingBalance = 0L
        accounts.forEach { account ->
            val convertedBalance = if (account.currency == defaultCurrency) {
                account.initialBalanceMinor
            } else {
                val rates = ratesByCurrency[account.currency].orEmpty()
                val anchorDate = activityDates[account.id] ?: rates.minOfOrNull { it.date }
                anchorDate?.let { findNearestRate(rates, it)?.convert(account.initialBalanceMinor) }
            }
            if (convertedBalance != null) {
                openingBalance = FinancialMath.add(openingBalance, convertedBalance)
            }
        }

        val movements = buildList {
            transactions.forEach { transaction ->
                val amount = transaction.defaultCurrencyAmountMinor
                if (amount != null && transaction.defaultCurrency == defaultCurrency) {
                    add(
                        NetWorthMovement(
                            transaction.date,
                            if (transaction.type == TransactionType.INCOME) amount else Math.negateExact(amount),
                        ),
                    )
                }
            }
            adjustments.forEach { adjustment ->
                val account = accountsById[adjustment.trackingAccountId] ?: return@forEach
                val amount = if (account.currency == defaultCurrency) {
                    adjustment.adjustmentAmountMinor
                } else {
                    findNearestRate(ratesByCurrency[account.currency].orEmpty(), adjustment.date)
                        ?.convert(adjustment.adjustmentAmountMinor)
                }
                if (amount != null) {
                    add(NetWorthMovement(adjustment.date, amount))
                }
            }
        }.sortedBy { it.date }

        val earliestActivity = sequence {
            yieldAll(transactions.map { it.date })
            yieldAll(transfers.map { it.date })
            yieldAll(adjustments.map { it.date })
        }.minOrNull()
        val from = requestedFrom ?: earliestActivity ?: to
        val granularity = resolveGranularity(requestedGranularity, from, to)
        var balance = openingBalance
        var movementIndex = 0
        val points = createBuckets(from, to, granularity).map { bucket ->
            val bucketEnd = minOf(bucketEnd(bucket, granularity), to)
            while (movementIndex < movements.size && !movements[movementIndex].date.isAfter(bucketEnd)) {
                balance = FinancialMath.add(balance, movements[movementIndex].amountMinor)
                movementIndex++
            }
            TransactionTimeSeriesPoint(bucket, defaultCurrency, balance)
        }
        return TransactionTimeSeries(granularity, from, to, points)
    }
}

private fun MutableMap<Long, LocalDate>.recordEarliest(accountId: Long, date: LocalDate) {
    compute(accountId) { _, current -> if (current == null || date.isBefore(current)) date else current }
}

private fun findNearestRate(rates: List<CurrencyRate>, anchorDate: LocalDate): CurrencyRate? = rates.minWithOrNull(
    compareBy<CurrencyRate> { kotlin.math.abs(ChronoUnit.DAYS.between(anchorDate, it.date)) }
        .thenBy { it.date }
        .thenBy { it.transactionId },
)

private data class CurrencyRate(
    val currency: String,
    val date: LocalDate,
    val transactionId: Long,
    val foreignAmountMinor: Long,
    val defaultAmountMinor: Long,
) {
    fun convert(amountMinor: Long): Long = BigDecimal.valueOf(amountMinor)
        .multiply(BigDecimal.valueOf(defaultAmountMinor))
        .divide(BigDecimal.valueOf(foreignAmountMinor), 0, RoundingMode.HALF_UP)
        .longValueExact()
}

private data class NetWorthMovement(val date: LocalDate, val amountMinor: Long)

private fun resolveGranularity(
    requested: TransactionTimeSeriesGranularity,
    from: LocalDate,
    to: LocalDate,
): TransactionTimeSeriesGranularity {
    if (requested != TransactionTimeSeriesGranularity.AUTO) {
        return requested
    }
    val monthBuckets = ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1
    if (monthBuckets >= 10) {
        return TransactionTimeSeriesGranularity.MONTH
    }
    val firstWeek = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val lastWeek = to.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return if (ChronoUnit.WEEKS.between(firstWeek, lastWeek) + 1 >= 10) {
        TransactionTimeSeriesGranularity.WEEK
    } else {
        TransactionTimeSeriesGranularity.DAY
    }
}

private fun createBuckets(
    from: LocalDate,
    to: LocalDate,
    granularity: TransactionTimeSeriesGranularity,
): List<LocalDate> {
    var bucket = when (granularity) {
        TransactionTimeSeriesGranularity.DAY -> from
        TransactionTimeSeriesGranularity.WEEK -> from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        TransactionTimeSeriesGranularity.MONTH -> YearMonth.from(from).atDay(1)
        TransactionTimeSeriesGranularity.AUTO -> error("AUTO granularity must be resolved before creating buckets")
    }
    return buildList {
        while (!bucket.isAfter(to)) {
            add(bucket)
            bucket = when (granularity) {
                TransactionTimeSeriesGranularity.DAY -> bucket.plusDays(1)
                TransactionTimeSeriesGranularity.WEEK -> bucket.plusWeeks(1)
                TransactionTimeSeriesGranularity.MONTH -> bucket.plusMonths(1)
                TransactionTimeSeriesGranularity.AUTO -> error("AUTO granularity must be resolved before creating buckets")
            }
        }
    }
}

private fun bucketEnd(bucket: LocalDate, granularity: TransactionTimeSeriesGranularity): LocalDate = when (granularity) {
    TransactionTimeSeriesGranularity.DAY -> bucket
    TransactionTimeSeriesGranularity.WEEK -> bucket.plusDays(6)
    TransactionTimeSeriesGranularity.MONTH -> YearMonth.from(bucket).atEndOfMonth()
    TransactionTimeSeriesGranularity.AUTO -> error("AUTO granularity must be resolved before finding a bucket end")
}
