package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.sql.SQLException
import javax.sql.DataSource

@Singleton
open class TransactionDefaultCurrencyService(
    private val trackingAccountRepository: TrackingAccountRepository,
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(TransactionDefaultCurrencyService::class.java)

    @Transactional
    open fun lockForUser(userId: Long) {
        lockUserValuation(userId)
    }

    @Transactional
    open fun recalculateTransaction(userId: Long, transactionId: Long) {
        lockUserValuation(userId)
        val updatedCount = recalculate(userId, "tx.id = ?", listOf(transactionId))
        logger.info(
            "Default-currency projection recalculated for transaction: userId={}, transactionId={}, updatedCount={}",
            userId,
            transactionId,
            updatedCount,
        )
    }

    @Transactional
    open fun recalculateTransactions(userId: Long, transactionIds: Collection<Long>) {
        val uniqueIds = transactionIds.distinct()
        if (uniqueIds.isEmpty()) {
            return
        }
        lockUserValuation(userId)
        val updatedCount = uniqueIds.chunked(MAX_SCOPE_SIZE).sumOf { ids ->
            recalculate(userId, "tx.id IN (${ids.joinToString(",") { "?" }})", ids)
        }
        if (uniqueIds.size == 1) {
            logger.info(
                "Default-currency projection recalculated for transaction: userId={}, transactionId={}, updatedCount={}",
                userId,
                uniqueIds.single(),
                updatedCount,
            )
        } else {
            logger.info(
                "Default-currency projections recalculated for changed transactions: userId={}, requestedCount={}, updatedCount={}",
                userId,
                uniqueIds.size,
                updatedCount,
            )
        }
    }

    @Transactional
    open fun recalculateForCurrencies(userId: Long, currencies: Collection<String>) {
        val uniqueCurrencies = currencies.distinct()
        if (uniqueCurrencies.isEmpty()) {
            return
        }
        lockUserValuation(userId)
        val updatedCount = recalculateCurrencies(userId, uniqueCurrencies)
        logger.info(
            "Default-currency projections recalculated for currencies: userId={}, currencies={}, updatedCount={}",
            userId,
            uniqueCurrencies.sorted(),
            updatedCount,
        )
    }

    private fun recalculateCurrencies(userId: Long, currencies: Collection<String>): Int =
        currencies.distinct().chunked(MAX_SCOPE_SIZE).sumOf { currencyChunk ->
            recalculateMatchingTransactions(
                userId,
                "transaction_account.currency IN (${currencyChunk.joinToString(",") { "?" }})",
                currencyChunk,
            )
        }

    @Transactional
    open fun recalculateForChangedTransfers(userId: Long, transfers: Collection<FundsTransfer>) {
        if (transfers.isEmpty()) {
            return
        }
        lockUserValuation(userId)
        val accounts = trackingAccountRepository.findByUserIdOrderByName(userId).associateBy { it.id!! }
        val defaultAccount = trackingAccountRepository.findByUserIdAndIsDefaultTrue(userId)
            ?: error("User $userId has transfers but no default tracking account")
        val currencyPairs = linkedSetOf<String>()
        val affectedCurrencies = transfers.mapNotNull { transfer ->
            val sourceCurrency = accounts[transfer.sourceAccountId]?.currency
                ?: error("Transfer ${transfer.id} references a missing source account")
            val targetCurrency = accounts[transfer.targetAccountId]?.currency
                ?: error("Transfer ${transfer.id} references a missing target account")
            currencyPairs += "$sourceCurrency->$targetCurrency"
            when {
                sourceCurrency == defaultAccount.currency && targetCurrency != defaultAccount.currency -> targetCurrency
                targetCurrency == defaultAccount.currency && sourceCurrency != defaultAccount.currency -> sourceCurrency
                else -> null
            }
        }
        val updatedCount = recalculateCurrencies(userId, affectedCurrencies)
        val transferIds = transfers.mapNotNull { it.id }.distinct()
        if (transferIds.size == 1) {
            logger.info(
                "Default-currency projections recalculated after transfer: userId={}, transferId={}, currencyPairs={}, affectedCurrencies={}, updatedCount={}",
                userId,
                transferIds.single(),
                currencyPairs,
                affectedCurrencies.distinct().sorted(),
                updatedCount,
            )
        } else {
            logger.info(
                "Default-currency projections recalculated after transfers: userId={}, transferCount={}, currencyPairs={}, affectedCurrencies={}, updatedCount={}",
                userId,
                transferIds.size,
                currencyPairs,
                affectedCurrencies.distinct().sorted(),
                updatedCount,
            )
        }
    }

    @Transactional
    open fun recalculateForAccountCurrencyChange(
        userId: Long,
        accountId: Long,
        oldCurrency: String,
        newCurrency: String,
    ) {
        lockUserValuation(userId)
        val defaultCurrency = trackingAccountRepository.findByUserIdAndIsDefaultTrue(userId)?.currency
            ?: error("User $userId has transactions but no default tracking account")
        val affectedCurrencies = mutableSetOf(oldCurrency, newCurrency)
        if (oldCurrency == defaultCurrency || newCurrency == defaultCurrency) {
            affectedCurrencies += findTransferCounterpartCurrencies(userId, accountId)
        }
        val updatedCount = recalculateCurrencies(userId, affectedCurrencies)
        logger.info(
            "Default-currency projections recalculated after account currency change: userId={}, accountId={}, oldCurrency={}, newCurrency={}, affectedCurrencies={}, updatedCount={}",
            userId,
            accountId,
            oldCurrency,
            newCurrency,
            affectedCurrencies.sorted(),
            updatedCount,
        )
    }

    @Transactional
    open fun recalculateForUser(userId: Long) {
        lockUserValuation(userId)
        val updatedCount = recalculateMatchingTransactions(userId, "TRUE", emptyList())
        logger.info(
            "Default-currency projections fully recalculated: userId={}, updatedCount={}",
            userId,
            updatedCount,
        )
    }

    private fun lockUserValuation(userId: Long) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                statement.setLong(1, userId xor Long.MIN_VALUE)
                statement.execute()
            }
        }
    }

    private fun recalculateMatchingTransactions(userId: Long, scopeSql: String, scopeParameters: List<Any>): Int =
        findTransactionIds(userId, scopeSql, scopeParameters)
            .chunked(MAX_SCOPE_SIZE)
            .sumOf { transactionIds ->
                recalculate(
                    userId,
                    "tx.id IN (${transactionIds.joinToString(",") { "?" }})",
                    transactionIds,
                )
            }

    private fun findTransactionIds(
        userId: Long,
        scopeSql: String,
        scopeParameters: List<Any>,
    ): List<Long> {
        val sql = """
            SELECT tx.id
            FROM transactions tx
            JOIN tracking_accounts transaction_account ON transaction_account.id = tx.tracking_account_id
            WHERE tx.user_id = ?
              AND $scopeSql
            ORDER BY tx.id
        """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, userId)
                scopeParameters.forEachIndexed { index, parameter -> statement.setObject(index + 2, parameter) }
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.getLong("id"))
                        }
                    }
                }
            }
        }
    }

    private fun recalculate(userId: Long, scopeSql: String, scopeParameters: List<Any>): Int {
        val sql = """
            WITH default_account AS (
                SELECT user_id, currency
                FROM tracking_accounts
                WHERE user_id = ? AND is_default = TRUE
            ),
            valuations AS (
                SELECT tx.id,
                       default_account.currency AS default_currency,
                       CASE
                           WHEN transaction_account.currency = default_account.currency THEN tx.amount_minor
                           WHEN evidence.id IS NULL THEN NULL
                           ELSE ROUND(
                               tx.amount_minor::NUMERIC * evidence.default_amount_minor::NUMERIC
                                   / evidence.foreign_amount_minor::NUMERIC
                           )::BIGINT
                       END AS default_currency_amount_minor,
                       CASE
                           WHEN transaction_account.currency = default_account.currency THEN 'SAME_CURRENCY'
                           WHEN evidence.id IS NULL THEN 'UNAVAILABLE'
                           ELSE 'ACTUAL_TRANSFER'
                       END AS conversion_source,
                       CASE
                           WHEN transaction_account.currency = default_account.currency THEN NULL
                           ELSE evidence.id
                       END AS conversion_transfer_id
                FROM transactions tx
                JOIN tracking_accounts transaction_account ON transaction_account.id = tx.tracking_account_id
                JOIN default_account ON default_account.user_id = tx.user_id
                LEFT JOIN LATERAL (
                    SELECT transfer.id,
                           CASE
                               WHEN source_account.currency = transaction_account.currency THEN transfer.source_amount_minor
                               ELSE transfer.target_amount_minor
                           END AS foreign_amount_minor,
                           CASE
                               WHEN source_account.currency = default_account.currency THEN transfer.source_amount_minor
                               ELSE transfer.target_amount_minor
                           END AS default_amount_minor
                    FROM funds_transfers transfer
                    JOIN tracking_accounts source_account ON source_account.id = transfer.source_account_id
                    JOIN tracking_accounts target_account ON target_account.id = transfer.target_account_id
                    WHERE transfer.user_id = tx.user_id
                      AND (
                          (source_account.currency = transaction_account.currency AND target_account.currency = default_account.currency)
                          OR
                          (source_account.currency = default_account.currency AND target_account.currency = transaction_account.currency)
                      )
                    ORDER BY
                        CASE
                            WHEN tx.type = 'INCOME'
                                AND source_account.currency = transaction_account.currency
                                AND transfer.date >= tx.date THEN 0
                            WHEN tx.type = 'EXPENSE'
                                AND target_account.currency = transaction_account.currency
                                AND transfer.date <= tx.date THEN 0
                            WHEN tx.type = 'INCOME' AND source_account.currency = transaction_account.currency THEN 1
                            WHEN tx.type = 'EXPENSE' AND target_account.currency = transaction_account.currency THEN 1
                            ELSE 2
                        END,
                        CASE
                            WHEN source_account.currency = transaction_account.currency
                                AND transfer.source_account_id = tx.tracking_account_id THEN 0
                            WHEN target_account.currency = transaction_account.currency
                                AND transfer.target_account_id = tx.tracking_account_id THEN 0
                            ELSE 1
                        END,
                        CASE
                            WHEN source_account.currency = transaction_account.currency
                                AND transfer.source_amount_minor = tx.amount_minor THEN 0
                            WHEN target_account.currency = transaction_account.currency
                                AND transfer.target_amount_minor = tx.amount_minor THEN 0
                            ELSE 1
                        END,
                        ABS(transfer.date - tx.date),
                        transfer.id
                    LIMIT 1
                ) evidence ON transaction_account.currency <> default_account.currency
                WHERE $scopeSql
            )
            UPDATE transactions tx
            SET default_currency_amount_minor = valuations.default_currency_amount_minor,
                default_currency = valuations.default_currency,
                default_currency_conversion_source = valuations.conversion_source,
                default_currency_conversion_transfer_id = valuations.conversion_transfer_id
            FROM valuations
            WHERE tx.id = valuations.id
        """.trimIndent()

        try {
            return dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, userId)
                    scopeParameters.forEachIndexed { index, parameter -> statement.setObject(index + 2, parameter) }
                    statement.executeUpdate()
                }
            }
        } catch (exception: SQLException) {
            if (exception.sqlState == NUMERIC_VALUE_OUT_OF_RANGE_SQL_STATE) {
                throw ArithmeticException("Default-currency amount is outside the Long range").apply { initCause(exception) }
            }
            throw exception
        }
    }

    private fun findTransferCounterpartCurrencies(userId: Long, accountId: Long): Set<String> {
        val sql = """
            SELECT DISTINCT CASE
                WHEN transfer.source_account_id = ? THEN target_account.currency
                ELSE source_account.currency
            END AS counterpart_currency
            FROM funds_transfers transfer
            JOIN tracking_accounts source_account ON source_account.id = transfer.source_account_id
            JOIN tracking_accounts target_account ON target_account.id = transfer.target_account_id
            WHERE transfer.user_id = ?
              AND (transfer.source_account_id = ? OR transfer.target_account_id = ?)
        """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, accountId)
                statement.setLong(2, userId)
                statement.setLong(3, accountId)
                statement.setLong(4, accountId)
                statement.executeQuery().use { resultSet ->
                    buildSet {
                        while (resultSet.next()) {
                            add(resultSet.getString("counterpart_currency"))
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_SCOPE_SIZE = 500
        const val NUMERIC_VALUE_OUT_OF_RANGE_SQL_STATE = "22003"
    }
}
