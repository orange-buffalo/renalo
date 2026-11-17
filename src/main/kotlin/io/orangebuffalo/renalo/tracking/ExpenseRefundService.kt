package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.security.MessageDigest
import java.time.LocalDate
import javax.sql.DataSource

enum class RefundPeriod(val months: Long?) {
    THREE_MONTHS(3), SIX_MONTHS(6), YEAR(12), TWO_YEARS(24), FULL_HISTORY(null),
}

enum class RefundMatchKind { EXPENSE, RECURRING_SERIES }

data class RefundSearch(
    val currency: String,
    val amountMinor: Long,
    val period: RefundPeriod = RefundPeriod.SIX_MONTHS,
)

data class SaveExpenseRefund(
    val search: RefundSearch,
    val transactionId: Long,
    val kind: RefundMatchKind,
    val fingerprint: String,
)

data class ExpenseRefundMatch(
    val transactionId: Long,
    val kind: RefundMatchKind,
    val fingerprint: String,
    val payments: List<TransactionResponse>,
    val removedExpenseCount: Int,
    val stopsRecurrence: Boolean,
)

@Singleton
open class ExpenseRefundService(
    private val dataSource: DataSource,
    private val transactions: TransactionRepository,
    private val transactionService: TransactionService,
    private val projections: TransactionDefaultCurrencyService,
) {
    @Transactional(readOnly = true)
    open fun search(userId: Long, search: RefundSearch, today: LocalDate): List<ExpenseRefundMatch> {
        val from = search.period.months?.let(today::minusMonths) ?: LocalDate.of(1, 1, 1)
        // Filter discovery in the database, but sum the entire executed series, not just the date window.
        val sql = """
            SELECT t.id, 'EXPENSE' AS kind
            FROM transactions t
            JOIN tracking_accounts a ON a.id = t.tracking_account_id AND a.user_id = t.user_id
            WHERE t.user_id = ? AND t.type = 'EXPENSE' AND a.currency = ?
              AND t.amount_minor = ? AND t.date BETWEEN ? AND ?
            UNION ALL
            SELECT MIN(t.id), 'RECURRING_SERIES' AS kind
            FROM transactions t
            JOIN tracking_accounts a ON a.id = t.tracking_account_id AND a.user_id = t.user_id
            JOIN recurring_transaction_rules r ON r.id = t.recurring_rule_id AND r.user_id = t.user_id
            WHERE t.user_id = ? AND t.type = 'EXPENSE' AND t.date <= ? AND r.status <> 'DELETED'
            GROUP BY t.recurring_rule_id
            HAVING COUNT(*) > 1 AND COUNT(DISTINCT a.currency) = 1 AND MIN(a.currency) = ?
              AND SUM(t.amount_minor) = ? AND BOOL_OR(t.date >= ?)
            ORDER BY kind, id
        """.trimIndent()
        val candidates = dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                listOf(userId, search.currency, search.amountMinor, from, today, userId, today, search.currency, search.amountMinor, from)
                    .forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(result.getLong("id") to RefundMatchKind.valueOf(result.getString("kind")))
                    }
                }
            }
        }
        return candidates.map { (id, kind) -> match(userId, id, kind, today) }
    }

    @Transactional
    open fun save(userId: Long, request: SaveExpenseRefund, today: LocalDate): Boolean {
        projections.lockForUser(userId)
        val candidate = search(userId, request.search, today).find {
            it.transactionId == request.transactionId && it.kind == request.kind && it.fingerprint == request.fingerprint
        } ?: return false
        val scope = when {
            candidate.kind == RefundMatchKind.RECURRING_SERIES -> RecurringTransactionDeleteScope.ALL_OCCURRENCES
            candidate.stopsRecurrence -> RecurringTransactionDeleteScope.THIS_AND_ALL_FOLLOWING_OCCURRENCES
            else -> null
        }
        check(
            transactionService.deleteTransaction(
                userId, TransactionType.EXPENSE, candidate.transactionId, DeleteTransactionRequest(scope),
            ) == DeleteTransactionResult.Deleted,
        )
        return true
    }

    private fun match(userId: Long, id: Long, kind: RefundMatchKind, today: LocalDate): ExpenseRefundMatch {
        val selected = checkNotNull(transactionService.findTransaction(userId, TransactionType.EXPENSE, id))
        val rule = selected.recurringRule
        val series = rule?.let { transactions.findByRecurringRuleIdOrderByRecurringInstanceDate(it.id!!) }
        val removed = when {
            kind == RefundMatchKind.RECURRING_SERIES -> checkNotNull(series)
            series != null -> series.filter { it.recurringInstanceDate!! >= selected.transaction.recurringInstanceDate!! }
            else -> listOf(selected.transaction)
        }
        val payments = if (kind == RefundMatchKind.RECURRING_SERIES) {
            removed.filter { it.date <= today }.map {
                checkNotNull(transactionService.findTransaction(userId, TransactionType.EXPENSE, it.id!!)).toResponse()
            }
        } else listOf(selected.toResponse())
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("$kind|$rule|$removed|$payments".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return ExpenseRefundMatch(id, kind, fingerprint, payments, removed.size, rule != null)
    }
}
