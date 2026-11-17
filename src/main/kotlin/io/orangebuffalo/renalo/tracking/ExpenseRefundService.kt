package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import io.micronaut.transaction.TransactionDefinition.Isolation
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
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    open fun search(userId: Long, search: RefundSearch, today: LocalDate): List<ExpenseRefundMatch> {
        val context = MatchContext(userId, today)
        return discover(userId, search, today).map { (id, kind) -> context.match(id, kind) }
    }

    private fun discover(
        userId: Long,
        search: RefundSearch,
        today: LocalDate,
        selection: SaveExpenseRefund? = null,
    ): List<Pair<Long, RefundMatchKind>> {
        val from = search.period.months?.let(today::minusMonths) ?: LocalDate.of(1, 1, 1)
        // Filter discovery in the database, but sum the entire executed series, not just the date window.
        val sql = """
            SELECT * FROM (
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
            ) matches
            ${if (selection != null) "WHERE id = ? AND kind = ?" else ""}
            ORDER BY kind, id
        """.trimIndent()
        val candidates = dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                val parameters = listOf(userId, search.currency, search.amountMinor, from, today, userId, today, search.currency, search.amountMinor, from) +
                    (selection?.let { listOf(it.transactionId, it.kind.name) } ?: emptyList())
                parameters
                    .forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(result.getLong("id") to RefundMatchKind.valueOf(result.getString("kind")))
                    }
                }
            }
        }
        return candidates
    }

    @Transactional
    open fun save(userId: Long, request: SaveExpenseRefund, today: LocalDate): Boolean {
        projections.lockForUser(userId)
        // Only hydrate and fingerprint the selected match while holding the user-wide write lock.
        val (id, kind) = discover(userId, request.search, today, request).singleOrNull() ?: return false
        val candidate = MatchContext(userId, today).match(id, kind)
        if (candidate.fingerprint != request.fingerprint) return false
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

    private inner class MatchContext(private val userId: Long, private val today: LocalDate) {
        private val details = mutableMapOf<Long, TransactionDetails>()
        private val series = mutableMapOf<Long, SeriesSnapshot>()

        private fun details(id: Long): TransactionDetails = details.getOrPut(id) {
            checkNotNull(transactionService.findTransaction(userId, TransactionType.EXPENSE, id))
        }

        fun match(id: Long, kind: RefundMatchKind): ExpenseRefundMatch {
            val selected = details(id)
            val rule = selected.recurringRule
            val snapshot = rule?.let {
                series.getOrPut(it.id!!) {
                    val rows = transactions.findByRecurringRuleIdOrderByRecurringInstanceDate(it.id!!)
                    // Share suffix summaries across matching instances: never rescan/hash a series per match.
                    var suffix = RemovalSummary(0, "")
                    val suffixes = rows.asReversed().associate { row ->
                        suffix = RemovalSummary(suffix.count + 1, hash("$row|${suffix.fingerprint}"))
                        row.id!! to suffix
                    }
                    SeriesSnapshot(rows, suffixes)
                }
            }
            val removed = when {
                kind == RefundMatchKind.RECURRING_SERIES -> checkNotNull(snapshot).suffixes.getValue(snapshot.rows.first().id!!)
                snapshot != null -> snapshot.suffixes.getValue(id)
                else -> RemovalSummary(1, hash(selected.transaction.toString()))
            }
            val payments = if (kind == RefundMatchKind.RECURRING_SERIES) {
                checkNotNull(snapshot).rows.filter { it.date <= today }.map { details(it.id!!).toResponse() }
            } else listOf(selected.toResponse())
            val fingerprint = hash("$kind|$rule|${removed.fingerprint}|$payments")
            return ExpenseRefundMatch(id, kind, fingerprint, payments, removed.count, rule != null)
        }
    }

    private data class RemovalSummary(val count: Int, val fingerprint: String)
    private data class SeriesSnapshot(val rows: List<Transaction>, val suffixes: Map<Long, RemovalSummary>)

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
