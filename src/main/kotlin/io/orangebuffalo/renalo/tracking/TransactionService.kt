package io.orangebuffalo.renalo.tracking

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import io.micronaut.transaction.annotation.Transactional
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import jakarta.inject.Singleton
import java.sql.ResultSet
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import javax.sql.DataSource

@Singleton
open class TransactionService(
    private val dataSource: DataSource,
    private val transactionRepository: TransactionRepository,
    private val trackingAccountRepository: TrackingAccountRepository,
    private val expenseCategoryRepository: ExpenseCategoryRepository,
    private val incomeCategoryRepository: IncomeCategoryRepository,
    private val recurringTransactionRuleRepository: RecurringTransactionRuleRepository,
    private val recurringTransactionSkipRepository: RecurringTransactionSkipRepository,
    private val recurringTransactionGenerationService: RecurringTransactionGenerationService,
    private val transactionDefaultCurrencyService: TransactionDefaultCurrencyService,
) {
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val metadataType = object : TypeReference<Map<String, String>>() {}

    @Transactional(readOnly = true)
    open fun listTransactions(
        userId: Long,
        type: TransactionType,
        filter: TransactionDateFilter = TransactionDateFilter(),
    ): List<TransactionDetails> {
        val transactions = findTransactions(userId, type, filter)
        return transactions.mapNotNull { it.toDetails(userId, type) }
    }

    @Transactional(readOnly = true)
    open fun getTimeSeries(
        userId: Long,
        type: TransactionType,
        filter: TransactionDateFilter,
        granularity: TransactionTimeSeriesGranularity,
    ): TransactionTimeSeries {
        val queryFilter = transactionQueryFilter(userId, type, filter)
        val dataBounds = findTimeSeriesBounds(type, queryFilter)
        val from = filter.from ?: dataBounds?.first
        val to = filter.to ?: dataBounds?.second
        val resolvedGranularity = resolveGranularity(granularity, from, to)
        val points = if (dataBounds == null) {
            emptyList()
        } else {
            findTimeSeriesPoints(type, queryFilter, resolvedGranularity)
        }
        return TransactionTimeSeries(resolvedGranularity, from, to, points)
    }

    @Transactional(readOnly = true)
    open fun getCategoryTotals(
        userId: Long,
        type: TransactionType,
        filter: TransactionDateFilter,
    ): List<TransactionCategoryTotal> {
        val queryFilter = transactionQueryFilter(userId, type, filter)
        val sql = """
            SELECT c.id AS category_id,
                   c.name AS category_name,
                   default_account.currency,
                   SUM(t.default_currency_amount_minor) AS amount_minor
            FROM transactions t
            ${timeSeriesJoins(type)}
            WHERE ${queryFilter.whereClause}
              AND t.default_currency_amount_minor IS NOT NULL
              AND t.default_currency = default_account.currency
            GROUP BY c.id, c.name, default_account.currency
            ORDER BY amount_minor DESC, c.name, c.id
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                queryFilter.parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
                statement.executeQuery().use { resultSet ->
                    val totals = mutableListOf<TransactionCategoryTotal>()
                    while (resultSet.next()) {
                        totals += TransactionCategoryTotal(
                            categoryId = resultSet.getLong("category_id"),
                            categoryName = resultSet.getString("category_name"),
                            currency = resultSet.getString("currency"),
                            amountMinor = resultSet.getBigDecimal("amount_minor").longValueExact(),
                        )
                    }
                    return totals
                }
            }
        }
    }

    @Transactional(readOnly = true)
    open fun queryTransactions(
        userId: Long,
        type: TransactionType,
        criteria: TransactionQueryCriteria,
        orderBy: TransactionQueryOrder,
        direction: SortDirection,
        offset: Int,
        limit: Int,
    ): TransactionQueryResult {
        require(offset >= 0) { "offset must not be negative" }
        require(limit in 1..50) { "limit must be between 1 and 50" }
        val queryFilter = advancedTransactionQueryFilter(userId, type, criteria)
        val orderExpression = when (orderBy) {
            TransactionQueryOrder.ID -> "t.id"
            TransactionQueryOrder.ACCOUNT_ID -> "t.tracking_account_id"
            TransactionQueryOrder.CATEGORY_ID -> "t.category_id"
            TransactionQueryOrder.DATE -> "t.date"
            TransactionQueryOrder.AMOUNT -> "t.amount_minor"
            TransactionQueryOrder.CURRENCY -> "a.currency"
            TransactionQueryOrder.DEFAULT_CURRENCY_AMOUNT -> "t.default_currency_amount_minor"
            TransactionQueryOrder.DEFAULT_CURRENCY -> "t.default_currency"
            TransactionQueryOrder.CONVERSION_SOURCE -> "t.default_currency_conversion_source"
            TransactionQueryOrder.CONVERSION_TRANSFER_ID -> "t.default_currency_conversion_transfer_id"
            TransactionQueryOrder.NOTES -> "t.notes"
            TransactionQueryOrder.ACCOUNT_NAME -> "a.name"
            TransactionQueryOrder.CATEGORY_NAME -> "c.name"
            TransactionQueryOrder.RECURRING_RULE_ID -> "t.recurring_rule_id"
            TransactionQueryOrder.RECURRING_INSTANCE_DATE -> "t.recurring_instance_date"
            TransactionQueryOrder.RECURRING_LOCKED -> "t.recurring_locked"
            TransactionQueryOrder.METADATA_SOURCE -> "t.metadata ->> 'source'"
        }
        val orderDirection = direction.name
        val rowsSql = """
            SELECT t.id,
                   t.type,
                   t.tracking_account_id,
                   a.name AS account_name,
                   a.currency,
                   t.category_id,
                   c.name AS category_name,
                   t.date,
                   t.amount_minor,
                   t.default_currency_amount_minor,
                   t.default_currency,
                   t.default_currency_conversion_source,
                   t.default_currency_conversion_transfer_id,
                   t.notes,
                   t.metadata::text AS metadata,
                   t.recurring_rule_id,
                   t.recurring_instance_date,
                   t.recurring_locked
            FROM transactions t
            ${timeSeriesJoins(type)}
            WHERE ${queryFilter.whereClause}
            ORDER BY $orderExpression $orderDirection NULLS LAST${if (orderBy == TransactionQueryOrder.ID) "" else ", t.id $orderDirection"}
            LIMIT ? OFFSET ?
        """.trimIndent()
        val summarySql = """
            SELECT COUNT(*) AS total_count,
                   COUNT(*) FILTER (
                       WHERE t.default_currency_amount_minor IS NOT NULL
                         AND t.default_currency = default_account.currency
                   ) AS projected_count,
                   SUM(t.default_currency_amount_minor) FILTER (
                       WHERE t.default_currency_amount_minor IS NOT NULL
                         AND t.default_currency = default_account.currency
                   ) AS projected_sum,
                   MIN(t.default_currency_amount_minor) FILTER (
                       WHERE t.default_currency_amount_minor IS NOT NULL
                         AND t.default_currency = default_account.currency
                   ) AS projected_min,
                   MAX(t.default_currency_amount_minor) FILTER (
                       WHERE t.default_currency_amount_minor IS NOT NULL
                         AND t.default_currency = default_account.currency
                   ) AS projected_max,
                   ROUND(AVG(t.default_currency_amount_minor) FILTER (
                       WHERE t.default_currency_amount_minor IS NOT NULL
                         AND t.default_currency = default_account.currency
                   )) AS projected_average
            FROM transactions t
            ${timeSeriesJoins(type)}
            WHERE ${queryFilter.whereClause}
        """.trimIndent()
        val originalCurrencySummarySql = """
            SELECT a.currency,
                   COUNT(*) AS transaction_count,
                   SUM(t.amount_minor) AS amount_sum,
                   MIN(t.amount_minor) AS amount_min,
                   MAX(t.amount_minor) AS amount_max,
                   ROUND(AVG(t.amount_minor)) AS amount_average
            FROM transactions t
            ${timeSeriesJoins(type)}
            WHERE ${queryFilter.whereClause}
            GROUP BY a.currency
            ORDER BY a.currency
        """.trimIndent()

        dataSource.connection.use { connection ->
            val items = connection.prepareStatement(rowsSql).use { statement ->
                queryFilter.parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
                statement.setInt(queryFilter.parameters.size + 1, limit)
                statement.setInt(queryFilter.parameters.size + 2, offset)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                QueriedTransaction(
                                    id = resultSet.getLong("id"),
                                    type = TransactionType.valueOf(resultSet.getString("type")),
                                    accountId = resultSet.getLong("tracking_account_id"),
                                    accountName = resultSet.getString("account_name"),
                                    currency = resultSet.getString("currency"),
                                    categoryId = resultSet.getLong("category_id"),
                                    categoryName = resultSet.getString("category_name"),
                                    date = resultSet.getDate("date").toLocalDate(),
                                    amountMinor = resultSet.getLong("amount_minor"),
                                    defaultCurrencyAmountMinor = resultSet.getNullableLong("default_currency_amount_minor"),
                                    defaultCurrency = resultSet.getString("default_currency"),
                                    conversionSource = DefaultCurrencyConversionSource.valueOf(
                                        resultSet.getString("default_currency_conversion_source"),
                                    ),
                                    conversionTransferId = resultSet.getNullableLong("default_currency_conversion_transfer_id"),
                                    notes = resultSet.getString("notes"),
                                    metadata = resultSet.getString("metadata")?.let {
                                        objectMapper.readValue(it, metadataType)
                                    },
                                    recurringRuleId = resultSet.getNullableLong("recurring_rule_id"),
                                    recurringInstanceDate = resultSet.getDate("recurring_instance_date")?.toLocalDate(),
                                    recurringLocked = resultSet.getBoolean("recurring_locked"),
                                ),
                            )
                        }
                    }
                }
            }
            val summary = connection.prepareStatement(summarySql).use { statement ->
                queryFilter.parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    val totalCount = resultSet.getLong("total_count")
                    val projectedCount = resultSet.getLong("projected_count")
                    TransactionQuerySummary(
                        totalCount = totalCount,
                        defaultCurrency = trackingAccountRepository.findByUserIdAndIsDefaultTrue(userId)?.currency,
                        projectedCount = projectedCount,
                        unprojectedCount = totalCount - projectedCount,
                        projectedAmountSumMinor = resultSet.getBigDecimal("projected_sum")?.longValueExact(),
                        projectedAmountMinMinor = resultSet.getBigDecimal("projected_min")?.longValueExact(),
                        projectedAmountMaxMinor = resultSet.getBigDecimal("projected_max")?.longValueExact(),
                        projectedAmountAverageMinorRounded = resultSet.getBigDecimal("projected_average")?.longValueExact(),
                    )
                }
            }
            val originalCurrencySummaries = connection.prepareStatement(originalCurrencySummarySql).use { statement ->
                queryFilter.parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                OriginalCurrencyTransactionSummary(
                                    currency = resultSet.getString("currency"),
                                    transactionCount = resultSet.getLong("transaction_count"),
                                    amountSumMinor = resultSet.getBigDecimal("amount_sum").longValueExact(),
                                    amountMinMinor = resultSet.getLong("amount_min"),
                                    amountMaxMinor = resultSet.getLong("amount_max"),
                                    amountAverageMinorRounded = resultSet.getBigDecimal("amount_average").longValueExact(),
                                ),
                            )
                        }
                    }
                }
            }
            return TransactionQueryResult(
                items = items,
                offset = offset,
                limit = limit,
                hasMore = offset.toLong() + items.size < summary.totalCount,
                summary = summary,
                originalCurrencySummaries = originalCurrencySummaries,
            )
        }
    }

    private fun findTransactions(userId: Long, type: TransactionType, filter: TransactionDateFilter): List<Transaction> {
        val queryFilter = transactionQueryFilter(userId, type, filter)

        val sql = """
            SELECT t.id,
                   t.user_id,
                   t.type,
                   t.tracking_account_id,
                   t.category_id,
                   t.date,
                   t.amount_minor,
                   t.default_currency_amount_minor,
                   t.default_currency,
                   t.default_currency_conversion_source,
                   t.default_currency_conversion_transfer_id,
                   t.notes,
                   t.metadata,
                   t.recurring_rule_id,
                   t.recurring_instance_date,
                   t.recurring_locked
            FROM transactions t
            WHERE ${queryFilter.whereClause}
            ORDER BY t.date DESC
        """.trimIndent()

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                queryFilter.parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
                statement.executeQuery().use { resultSet ->
                    val transactions = mutableListOf<Transaction>()
                    while (resultSet.next()) {
                        transactions += resultSet.toTransaction()
                    }
                    return transactions
                }
            }
        }
    }

    private fun findTimeSeriesBounds(type: TransactionType, queryFilter: TransactionQueryFilter): Pair<LocalDate, LocalDate>? {
        val sql = """
            SELECT MIN(t.date) AS first_date,
                   MAX(t.date) AS last_date
            FROM transactions t
            ${timeSeriesJoins(type)}
            WHERE ${queryFilter.whereClause}
              AND t.default_currency_amount_minor IS NOT NULL
              AND t.default_currency = default_account.currency
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                queryFilter.parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    val firstDate = resultSet.getDate("first_date")?.toLocalDate() ?: return null
                    return firstDate to resultSet.getDate("last_date").toLocalDate()
                }
            }
        }
    }

    private fun findTimeSeriesPoints(
        type: TransactionType,
        queryFilter: TransactionQueryFilter,
        granularity: TransactionTimeSeriesGranularity,
    ): List<TransactionTimeSeriesPoint> {
        val bucketExpression = when (granularity) {
            TransactionTimeSeriesGranularity.DAY -> "t.date"
            TransactionTimeSeriesGranularity.WEEK -> "date_trunc('week', t.date)::date"
            TransactionTimeSeriesGranularity.MONTH -> "date_trunc('month', t.date)::date"
            TransactionTimeSeriesGranularity.AUTO -> error("AUTO granularity must be resolved before querying")
        }
        val sql = """
            SELECT $bucketExpression AS bucket,
                   default_account.currency,
                   SUM(t.default_currency_amount_minor) AS amount_minor
            FROM transactions t
            ${timeSeriesJoins(type)}
            WHERE ${queryFilter.whereClause}
              AND t.default_currency_amount_minor IS NOT NULL
              AND t.default_currency = default_account.currency
            GROUP BY bucket, default_account.currency
            ORDER BY bucket
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                queryFilter.parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
                statement.executeQuery().use { resultSet ->
                    val points = mutableListOf<TransactionTimeSeriesPoint>()
                    while (resultSet.next()) {
                        points += TransactionTimeSeriesPoint(
                            bucket = resultSet.getDate("bucket").toLocalDate(),
                            currency = resultSet.getString("currency"),
                            amountMinor = resultSet.getBigDecimal("amount_minor").longValueExact(),
                        )
                    }
                    return points
                }
            }
        }
    }

    private fun timeSeriesJoins(type: TransactionType): String {
        val categoryTable = when (type) {
            TransactionType.EXPENSE -> "expense_categories"
            TransactionType.INCOME -> "income_categories"
        }
        return """
            JOIN tracking_accounts a ON a.id = t.tracking_account_id AND a.user_id = t.user_id
            JOIN tracking_accounts default_account ON default_account.user_id = t.user_id AND default_account.is_default = TRUE
            JOIN $categoryTable c ON c.id = t.category_id AND c.user_id = t.user_id
        """.trimIndent()
    }

    private fun transactionQueryFilter(
        userId: Long,
        type: TransactionType,
        filter: TransactionDateFilter,
    ): TransactionQueryFilter {
        val whereClauses = mutableListOf("t.user_id = ?", "t.type = ?")
        val parameters = mutableListOf<Any>(userId, type.name)

        if (filter.from != null) {
            whereClauses += "t.date >= ?"
            parameters += filter.from
        }
        if (filter.to != null) {
            whereClauses += "t.date <= ?"
            parameters += filter.to
        }
        if (filter.categoryIds.isNotEmpty()) {
            whereClauses += "t.category_id IN (${filter.categoryIds.joinToString(",") { "?" }})"
            parameters.addAll(filter.categoryIds)
        }
        if (filter.excludedCategoryIds.isNotEmpty()) {
            whereClauses += "t.category_id NOT IN (${filter.excludedCategoryIds.joinToString(",") { "?" }})"
            parameters.addAll(filter.excludedCategoryIds)
        }
        if (filter.accountIds.isNotEmpty()) {
            whereClauses += "t.tracking_account_id IN (${filter.accountIds.joinToString(",") { "?" }})"
            parameters.addAll(filter.accountIds)
        }
        if (filter.excludedAccountIds.isNotEmpty()) {
            whereClauses += "t.tracking_account_id NOT IN (${filter.excludedAccountIds.joinToString(",") { "?" }})"
            parameters.addAll(filter.excludedAccountIds)
        }
        for (token in filter.notesTokens) {
            whereClauses += "LOWER(COALESCE(t.notes, '')) LIKE ?"
            parameters += "%${token.lowercase()}%"
        }
        return TransactionQueryFilter(whereClauses.joinToString(" AND "), parameters)
    }

    private fun advancedTransactionQueryFilter(
        userId: Long,
        type: TransactionType,
        criteria: TransactionQueryCriteria,
    ): TransactionQueryFilter {
        val baseFilter = transactionQueryFilter(
            userId,
            type,
            TransactionDateFilter(
                from = criteria.from,
                to = criteria.to,
                categoryIds = criteria.categoryIds,
                accountIds = criteria.accountIds,
                notesTokens = criteria.notesTokens,
            ),
        )
        val whereClauses = mutableListOf(baseFilter.whereClause)
        val parameters = baseFilter.parameters.toMutableList()

        if (criteria.transactionIds.isNotEmpty()) {
            whereClauses += "t.id IN (${criteria.transactionIds.joinToString(",") { "?" }})"
            parameters.addAll(criteria.transactionIds)
        }
        if (!criteria.accountNameQuery.isNullOrBlank()) {
            whereClauses += "LOWER(a.name) LIKE ?"
            parameters += "%${criteria.accountNameQuery.lowercase()}%"
        }
        if (!criteria.categoryNameQuery.isNullOrBlank()) {
            whereClauses += "LOWER(c.name) LIKE ?"
            parameters += "%${criteria.categoryNameQuery.lowercase()}%"
        }
        if (criteria.currencies.isNotEmpty()) {
            whereClauses += "a.currency IN (${criteria.currencies.joinToString(",") { "?" }})"
            parameters.addAll(criteria.currencies)
        }
        if (criteria.amountMinorFrom != null) {
            whereClauses += "t.amount_minor >= ?"
            parameters += criteria.amountMinorFrom
        }
        if (criteria.amountMinorTo != null) {
            whereClauses += "t.amount_minor <= ?"
            parameters += criteria.amountMinorTo
        }
        if (criteria.defaultCurrencyAmountMinorFrom != null) {
            whereClauses += "t.default_currency_amount_minor >= ?"
            parameters += criteria.defaultCurrencyAmountMinorFrom
        }
        if (criteria.defaultCurrencyAmountMinorTo != null) {
            whereClauses += "t.default_currency_amount_minor <= ?"
            parameters += criteria.defaultCurrencyAmountMinorTo
        }
        if (criteria.defaultCurrencies.isNotEmpty()) {
            whereClauses += "t.default_currency IN (${criteria.defaultCurrencies.joinToString(",") { "?" }})"
            parameters.addAll(criteria.defaultCurrencies)
        }
        if (criteria.conversionSources.isNotEmpty()) {
            whereClauses += "t.default_currency_conversion_source IN (${criteria.conversionSources.joinToString(",") { "?" }})"
            parameters.addAll(criteria.conversionSources.map { it.name })
        }
        if (criteria.conversionTransferIds.isNotEmpty()) {
            whereClauses += "t.default_currency_conversion_transfer_id IN (${criteria.conversionTransferIds.joinToString(",") { "?" }})"
            parameters.addAll(criteria.conversionTransferIds)
        }
        if (criteria.recurring != null) {
            whereClauses += if (criteria.recurring) "t.recurring_rule_id IS NOT NULL" else "t.recurring_rule_id IS NULL"
        }
        if (criteria.recurringRuleIds.isNotEmpty()) {
            whereClauses += "t.recurring_rule_id IN (${criteria.recurringRuleIds.joinToString(",") { "?" }})"
            parameters.addAll(criteria.recurringRuleIds)
        }
        if (criteria.recurringInstanceFrom != null) {
            whereClauses += "t.recurring_instance_date >= ?"
            parameters += criteria.recurringInstanceFrom
        }
        if (criteria.recurringInstanceTo != null) {
            whereClauses += "t.recurring_instance_date <= ?"
            parameters += criteria.recurringInstanceTo
        }
        if (criteria.recurringLocked != null) {
            whereClauses += "t.recurring_locked = ?"
            parameters += criteria.recurringLocked
        }
        if (criteria.metadataSources.isNotEmpty()) {
            whereClauses += "t.metadata ->> 'source' IN (${criteria.metadataSources.joinToString(",") { "?" }})"
            parameters.addAll(criteria.metadataSources)
        }
        return TransactionQueryFilter(whereClauses.joinToString(" AND "), parameters)
    }

    private fun resolveGranularity(
        requested: TransactionTimeSeriesGranularity,
        from: LocalDate?,
        to: LocalDate?,
    ): TransactionTimeSeriesGranularity {
        if (requested != TransactionTimeSeriesGranularity.AUTO || from == null || to == null) {
            return if (requested == TransactionTimeSeriesGranularity.AUTO) TransactionTimeSeriesGranularity.DAY else requested
        }
        val monthBuckets = ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1
        if (monthBuckets >= MINIMUM_TIME_SERIES_BUCKETS) {
            return TransactionTimeSeriesGranularity.MONTH
        }
        val firstWeek = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastWeek = to.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekBuckets = ChronoUnit.WEEKS.between(firstWeek, lastWeek) + 1
        return if (weekBuckets >= MINIMUM_TIME_SERIES_BUCKETS) {
            TransactionTimeSeriesGranularity.WEEK
        } else {
            TransactionTimeSeriesGranularity.DAY
        }
    }

    fun findTransaction(userId: Long, type: TransactionType, transactionId: Long): TransactionDetails? =
        transactionRepository.findByIdAndUserIdAndType(transactionId, userId, type)?.toDetails(userId, type)

    @Transactional
    open fun createTransaction(
        userId: Long,
        type: TransactionType,
        request: SaveTransactionRequest,
        currentDate: LocalDate,
    ): SaveTransactionResult {
        transactionDefaultCurrencyService.lockForUser(userId)
        val recurrence = request.recurrence
        return if (recurrence == null) {
            val result = saveTransaction(userId, type, null, request)?.let { SaveTransactionResult.Saved(it) }
                ?: SaveTransactionResult.BadRequest
            recalculateSavedTransaction(userId, result)
        } else {
            createRecurringTransaction(userId, type, request, recurrence, currentDate)
        }
    }

    @Transactional
    open fun updateTransaction(
        userId: Long,
        type: TransactionType,
        transactionId: Long,
        request: SaveTransactionRequest,
        currentDate: LocalDate,
    ): SaveTransactionResult {
        transactionDefaultCurrencyService.lockForUser(userId)
        val existingTransaction = transactionRepository.findByIdAndUserIdAndType(transactionId, userId, type)
            ?: return SaveTransactionResult.BadRequest

        if (existingTransaction.recurringRuleId != null) {
            return updateRecurringTransaction(userId, type, existingTransaction, request, currentDate)
        }

        val result = saveTransaction(userId, type, existingTransaction, request)?.let { SaveTransactionResult.Saved(it) }
            ?: SaveTransactionResult.BadRequest
        return recalculateSavedTransaction(userId, result)
    }

    @Transactional
    open fun deleteTransaction(
        userId: Long,
        type: TransactionType,
        transactionId: Long,
        request: DeleteTransactionRequest? = null,
    ): DeleteTransactionResult {
        transactionDefaultCurrencyService.lockForUser(userId)
        val transaction = transactionRepository.findByIdAndUserIdAndType(transactionId, userId, type)
            ?: return DeleteTransactionResult.NotFound
        if (transaction.recurringRuleId != null) {
            return deleteRecurringTransaction(userId, type, transaction, request)
        }

        transactionRepository.delete(transaction)
        return DeleteTransactionResult.Deleted
    }

    private fun deleteRecurringTransaction(
        userId: Long,
        type: TransactionType,
        transaction: Transaction,
        request: DeleteTransactionRequest?,
    ): DeleteTransactionResult {
        val scope = request?.recurringDeleteScope ?: return DeleteTransactionResult.BadRequest
        val rule = recurringTransactionRuleRepository.findByIdAndUserIdAndTransactionType(
            transaction.recurringRuleId!!,
            userId,
            type,
        ) ?: return DeleteTransactionResult.BadRequest
        val ruleId = rule.id ?: return DeleteTransactionResult.BadRequest
        val instanceDate = transaction.recurringInstanceDate ?: return DeleteTransactionResult.BadRequest

        when (scope) {
            RecurringTransactionDeleteScope.THIS_OCCURRENCE_ONLY -> {
                transactionRepository.delete(transaction)
                recurringTransactionSkipRepository.save(
                    RecurringTransactionSkip(recurringRuleId = ruleId, recurringInstanceDate = instanceDate),
                )
            }

            RecurringTransactionDeleteScope.THIS_AND_ALL_FOLLOWING_OCCURRENCES -> {
                recurringTransactionRuleRepository.update(rule.copy(endDate = instanceDate.minusDays(1)))
                transactionRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(ruleId)
                    .filter { it.recurringInstanceDate?.let { date -> date >= instanceDate } == true }
                    .forEach(transactionRepository::delete)
                recurringTransactionSkipRepository.findByRecurringRuleId(ruleId)
                    .filter { it.recurringInstanceDate >= instanceDate }
                    .forEach(recurringTransactionSkipRepository::delete)
            }

            RecurringTransactionDeleteScope.ALL_OCCURRENCES -> {
                recurringTransactionRuleRepository.update(rule.copy(status = RecurringTransactionRuleStatus.DELETED))
                transactionRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(ruleId)
                    .forEach(transactionRepository::delete)
                recurringTransactionSkipRepository.findByRecurringRuleId(ruleId)
                    .forEach(recurringTransactionSkipRepository::delete)
            }
        }

        return DeleteTransactionResult.Deleted
    }

    private fun saveTransaction(
        userId: Long,
        type: TransactionType,
        existingTransaction: Transaction?,
        request: SaveTransactionRequest,
    ): TransactionDetails? {
        if (request.amountMinor <= 0) {
            return null
        }
        val account = trackingAccountRepository.findByIdAndUserId(request.trackingAccountId, userId)
            ?: return null
        val category = findCategory(userId, type, request.categoryId)
            ?: return null
        val notes = request.notes?.trim()?.takeIf { it.isNotBlank() }
        val transaction = Transaction(
            id = existingTransaction?.id,
            userId = userId,
            type = type,
            trackingAccountId = account.id!!,
            categoryId = category.id,
            date = request.date,
            amountMinor = request.amountMinor,
            notes = notes,
            metadata = existingTransaction?.metadata,
        )

        val savedTransaction = if (existingTransaction == null) {
            transactionRepository.save(transaction)
        } else {
            transactionRepository.update(transaction)
        }
        return TransactionDetails(savedTransaction, account, category)
    }

    private fun createRecurringTransaction(
        userId: Long,
        type: TransactionType,
        request: SaveTransactionRequest,
        recurrence: SaveTransactionRecurrenceRequest,
        currentDate: LocalDate,
    ): SaveTransactionResult {
        if (request.amountMinor <= 0 || recurrence.frequency <= 0 || recurrence.endDate?.isBefore(request.date) == true) {
            return SaveTransactionResult.BadRequest
        }
        val account = trackingAccountRepository.findByIdAndUserId(request.trackingAccountId, userId)
            ?: return SaveTransactionResult.BadRequest
        val category = findCategory(userId, type, request.categoryId)
            ?: return SaveTransactionResult.BadRequest
        val notes = request.notes?.trim()?.takeIf { it.isNotBlank() }
        val rule = recurringTransactionRuleRepository.save(
            RecurringTransactionRule(
                userId = userId,
                transactionType = type,
                trackingAccountId = account.id!!,
                categoryId = category.id,
                startDate = request.date,
                endDate = recurrence.endDate,
                recurrenceFrequency = recurrence.frequency,
                recurrenceInterval = recurrence.interval,
                generatedUntil = request.date.minusDays(1),
                amountMinor = request.amountMinor,
                notes = notes,
            ),
        )

        recurringTransactionGenerationService.generateForRule(rule, currentDate)
        val firstTransaction = transactionRepository.findByRecurringRuleIdAndRecurringInstanceDate(rule.id!!, request.date)
            ?: error("Recurring transaction generation did not create the first occurrence for rule ${rule.id}")

        return SaveTransactionResult.Saved(TransactionDetails(firstTransaction, account, category, rule))
    }

    private fun updateRecurringTransaction(
        userId: Long,
        type: TransactionType,
        existingTransaction: Transaction,
        request: SaveTransactionRequest,
        currentDate: LocalDate,
    ): SaveTransactionResult {
        val scope = request.recurringEditScope ?: return SaveTransactionResult.BadRequest
        val rule = recurringTransactionRuleRepository.findByIdAndUserIdAndTransactionType(
            existingTransaction.recurringRuleId!!,
            userId,
            type,
        ) ?: return SaveTransactionResult.BadRequest
        val instanceDate = existingTransaction.recurringInstanceDate ?: return SaveTransactionResult.BadRequest
        if (request.amountMinor <= 0 || request.recurrence != null || request.date != instanceDate) {
            return SaveTransactionResult.BadRequest
        }
        val account = trackingAccountRepository.findByIdAndUserId(request.trackingAccountId, userId)
            ?: return SaveTransactionResult.BadRequest
        val category = findCategory(userId, type, request.categoryId)
            ?: return SaveTransactionResult.BadRequest
        val notes = request.notes?.trim()?.takeIf { it.isNotBlank() }

        return when (scope) {
            RecurringTransactionEditScope.THIS_OCCURRENCE_ONLY -> {
                val savedTransaction = transactionRepository.update(
                    existingTransaction.copy(
                        trackingAccountId = account.id!!,
                        categoryId = category.id,
                        date = request.date,
                        amountMinor = request.amountMinor,
                        notes = notes,
                        recurringLocked = true,
                    ),
                )
                transactionDefaultCurrencyService.recalculateTransaction(userId, savedTransaction.id!!)
                val refreshedTransaction = transactionRepository.findById(savedTransaction.id!!).orElseThrow()
                SaveTransactionResult.Saved(TransactionDetails(refreshedTransaction, account, category, rule))
            }

            RecurringTransactionEditScope.THIS_AND_ALL_FOLLOWING_OCCURRENCES -> {
                val newRule = recurringTransactionRuleRepository.save(
                    rule.copy(
                        id = null,
                        trackingAccountId = account.id!!,
                        categoryId = category.id,
                        startDate = instanceDate,
                        generatedUntil = instanceDate.minusDays(1),
                        lastGeneratedAt = null,
                        amountMinor = request.amountMinor,
                        notes = notes,
                    ),
                )
                recurringTransactionRuleRepository.update(rule.copy(endDate = instanceDate.minusDays(1)))
                val reassignedTransactionIds = reassignFollowingTransactionsToNewRule(
                    rule.id!!,
                    newRule,
                    instanceDate,
                    request.amountMinor,
                    notes,
                    existingTransaction.id!!,
                )
                recurringTransactionGenerationService.generateForRule(newRule, currentDate)
                transactionDefaultCurrencyService.recalculateTransactions(userId, reassignedTransactionIds)
                val savedTransaction = transactionRepository.findById(existingTransaction.id!!).orElseThrow()
                SaveTransactionResult.Saved(TransactionDetails(savedTransaction, account, category, newRule))
            }

            RecurringTransactionEditScope.ALL_OCCURRENCES -> {
                val updatedRule = recurringTransactionRuleRepository.update(
                    rule.copy(
                        trackingAccountId = account.id!!,
                        categoryId = category.id,
                        amountMinor = request.amountMinor,
                        notes = notes,
                    ),
                )
                val updatedTransactions = transactionRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!)
                    .filter { !it.recurringLocked || it.id == existingTransaction.id }
                    .map {
                        transactionRepository.update(
                            it.copy(
                                trackingAccountId = account.id!!,
                                categoryId = category.id,
                                amountMinor = request.amountMinor,
                                notes = notes,
                            ),
                        )
                    }
                transactionDefaultCurrencyService.recalculateTransactions(userId, updatedTransactions.map { it.id!! })
                val savedTransaction = transactionRepository.findById(existingTransaction.id!!).orElseThrow()
                SaveTransactionResult.Saved(TransactionDetails(savedTransaction, account, category, updatedRule))
            }
        }
    }

    private fun reassignFollowingTransactionsToNewRule(
        oldRuleId: Long,
        newRule: RecurringTransactionRule,
        instanceDate: LocalDate,
        amountMinor: Long,
        notes: String?,
        selectedTransactionId: Long,
    ): List<Long> = transactionRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(oldRuleId)
        .filter { it.recurringInstanceDate?.let { date -> date >= instanceDate } == true }
        .map { transaction ->
            val shouldApplyEditedValues = !transaction.recurringLocked || transaction.id == selectedTransactionId
            transactionRepository.update(
                transaction.copy(
                    trackingAccountId = if (shouldApplyEditedValues) newRule.trackingAccountId else transaction.trackingAccountId,
                    categoryId = if (shouldApplyEditedValues) newRule.categoryId else transaction.categoryId,
                    amountMinor = if (shouldApplyEditedValues) amountMinor else transaction.amountMinor,
                    notes = if (shouldApplyEditedValues) notes else transaction.notes,
                    recurringRuleId = newRule.id,
                ),
            ).id!!
        }

    private fun Transaction.toDetails(userId: Long, type: TransactionType): TransactionDetails? {
        val account = trackingAccountRepository.findByIdAndUserId(trackingAccountId, userId)
            ?: return null
        val category = findCategory(userId, type, categoryId)
            ?: return null
        val recurringRule = recurringRuleId?.let {
            recurringTransactionRuleRepository.findByIdAndUserIdAndTransactionType(it, userId, type)
        }
        return TransactionDetails(this, account, category, recurringRule)
    }

    private fun recalculateSavedTransaction(userId: Long, result: SaveTransactionResult): SaveTransactionResult {
        if (result !is SaveTransactionResult.Saved) {
            return result
        }
        transactionDefaultCurrencyService.recalculateTransaction(userId, result.transaction.transaction.id!!)
        val refreshedTransaction = transactionRepository.findById(result.transaction.transaction.id!!).orElseThrow()
        return SaveTransactionResult.Saved(result.transaction.copy(transaction = refreshedTransaction))
    }

    private fun findCategory(userId: Long, type: TransactionType, categoryId: Long): TransactionCategoryDetails? =
        when (type) {
            TransactionType.EXPENSE -> expenseCategoryRepository.findByIdAndUserId(categoryId, userId)
                ?.let { TransactionCategoryDetails(it.id!!, it.name) }
            TransactionType.INCOME -> incomeCategoryRepository.findByIdAndUserId(categoryId, userId)
                ?.let { TransactionCategoryDetails(it.id!!, it.name) }
        }
}

private const val MINIMUM_TIME_SERIES_BUCKETS = 10L

private data class TransactionQueryFilter(
    val whereClause: String,
    val parameters: List<Any>,
)

data class TransactionCategoryDetails(
    val id: Long,
    val name: String,
)

data class TransactionDetails(
    val transaction: Transaction,
    val account: TrackingAccount,
    val category: TransactionCategoryDetails,
    val recurringRule: RecurringTransactionRule? = null,
)

data class TransactionDateFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val categoryIds: List<Long> = emptyList(),
    val excludedCategoryIds: List<Long> = emptyList(),
    val accountIds: List<Long> = emptyList(),
    val excludedAccountIds: List<Long> = emptyList(),
    val notesTokens: List<String> = emptyList(),
)

data class TransactionQueryCriteria(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val categoryIds: List<Long> = emptyList(),
    val accountIds: List<Long> = emptyList(),
    val notesTokens: List<String> = emptyList(),
    val accountNameQuery: String? = null,
    val categoryNameQuery: String? = null,
    val transactionIds: List<Long> = emptyList(),
    val currencies: List<String> = emptyList(),
    val amountMinorFrom: Long? = null,
    val amountMinorTo: Long? = null,
    val defaultCurrencyAmountMinorFrom: Long? = null,
    val defaultCurrencyAmountMinorTo: Long? = null,
    val defaultCurrencies: List<String> = emptyList(),
    val conversionSources: List<DefaultCurrencyConversionSource> = emptyList(),
    val conversionTransferIds: List<Long> = emptyList(),
    val recurring: Boolean? = null,
    val recurringRuleIds: List<Long> = emptyList(),
    val recurringInstanceFrom: LocalDate? = null,
    val recurringInstanceTo: LocalDate? = null,
    val recurringLocked: Boolean? = null,
    val metadataSources: List<String> = emptyList(),
)

enum class TransactionTimeSeriesGranularity {
    AUTO,
    DAY,
    WEEK,
    MONTH,
}

data class TransactionTimeSeries(
    val granularity: TransactionTimeSeriesGranularity,
    val from: LocalDate?,
    val to: LocalDate?,
    val points: List<TransactionTimeSeriesPoint>,
)

data class TransactionTimeSeriesPoint(
    val bucket: LocalDate,
    val currency: String,
    val amountMinor: Long,
)

data class TransactionCategoryTotal(
    val categoryId: Long,
    val categoryName: String,
    val currency: String,
    val amountMinor: Long,
)

enum class TransactionQueryOrder {
    ID,
    ACCOUNT_ID,
    CATEGORY_ID,
    DATE,
    AMOUNT,
    CURRENCY,
    DEFAULT_CURRENCY_AMOUNT,
    DEFAULT_CURRENCY,
    CONVERSION_SOURCE,
    CONVERSION_TRANSFER_ID,
    NOTES,
    ACCOUNT_NAME,
    CATEGORY_NAME,
    RECURRING_RULE_ID,
    RECURRING_INSTANCE_DATE,
    RECURRING_LOCKED,
    METADATA_SOURCE,
}

enum class SortDirection {
    ASC,
    DESC,
}

data class TransactionQueryResult(
    val items: List<QueriedTransaction>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
    val summary: TransactionQuerySummary,
    val originalCurrencySummaries: List<OriginalCurrencyTransactionSummary>,
)

data class QueriedTransaction(
    val id: Long,
    val type: TransactionType,
    val accountId: Long,
    val accountName: String,
    val currency: String,
    val categoryId: Long,
    val categoryName: String,
    val date: LocalDate,
    val amountMinor: Long,
    val defaultCurrencyAmountMinor: Long?,
    val defaultCurrency: String?,
    val conversionSource: DefaultCurrencyConversionSource,
    val conversionTransferId: Long?,
    val notes: String?,
    val metadata: Map<String, String>?,
    val recurringRuleId: Long?,
    val recurringInstanceDate: LocalDate?,
    val recurringLocked: Boolean,
)

data class TransactionQuerySummary(
    val totalCount: Long,
    val defaultCurrency: String?,
    val projectedCount: Long,
    val unprojectedCount: Long,
    val projectedAmountSumMinor: Long?,
    val projectedAmountMinMinor: Long?,
    val projectedAmountMaxMinor: Long?,
    val projectedAmountAverageMinorRounded: Long?,
)

data class OriginalCurrencyTransactionSummary(
    val currency: String,
    val transactionCount: Long,
    val amountSumMinor: Long,
    val amountMinMinor: Long,
    val amountMaxMinor: Long,
    val amountAverageMinorRounded: Long,
)

private fun ResultSet.toTransaction() = Transaction(
    id = getLong("id"),
    userId = getLong("user_id"),
    type = TransactionType.valueOf(getString("type")),
    trackingAccountId = getLong("tracking_account_id"),
    categoryId = getLong("category_id"),
    date = getDate("date").toLocalDate(),
    amountMinor = getLong("amount_minor"),
    defaultCurrencyAmountMinor = getNullableLong("default_currency_amount_minor"),
    defaultCurrency = getString("default_currency"),
    defaultCurrencyConversionSource = DefaultCurrencyConversionSource.valueOf(getString("default_currency_conversion_source")),
    defaultCurrencyConversionTransferId = getNullableLong("default_currency_conversion_transfer_id"),
    notes = getString("notes"),
    metadata = getString("metadata")?.let { emptyMap() },
    recurringRuleId = getNullableLong("recurring_rule_id"),
    recurringInstanceDate = getDate("recurring_instance_date")?.toLocalDate(),
    recurringLocked = getBoolean("recurring_locked"),
)

private fun ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

sealed interface SaveTransactionResult {
    data class Saved(val transaction: TransactionDetails) : SaveTransactionResult

    data object BadRequest : SaveTransactionResult
}

data class SaveTransactionRequest(
    val trackingAccountId: Long,
    val categoryId: Long,
    val date: LocalDate,
    val amountMinor: Long,
    val notes: String? = null,
    val recurrence: SaveTransactionRecurrenceRequest? = null,
    val recurringEditScope: RecurringTransactionEditScope? = null,
)

data class SaveTransactionRecurrenceRequest(
    val frequency: Int,
    val interval: RecurrenceInterval,
    val endDate: LocalDate? = null,
)

sealed interface DeleteTransactionResult {
    data object Deleted : DeleteTransactionResult

    data object NotFound : DeleteTransactionResult

    data object BadRequest : DeleteTransactionResult
}

data class DeleteTransactionRequest(
    val recurringDeleteScope: RecurringTransactionDeleteScope? = null,
)

enum class RecurringTransactionEditScope {
    THIS_OCCURRENCE_ONLY,
    THIS_AND_ALL_FOLLOWING_OCCURRENCES,
    ALL_OCCURRENCES,
}

enum class RecurringTransactionDeleteScope {
    THIS_OCCURRENCE_ONLY,
    THIS_AND_ALL_FOLLOWING_OCCURRENCES,
    ALL_OCCURRENCES,
}
