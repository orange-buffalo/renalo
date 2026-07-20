package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import jakarta.inject.Singleton
import java.sql.ResultSet
import java.time.LocalDate
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
    @Transactional(readOnly = true)
    open fun listTransactions(
        userId: Long,
        type: TransactionType,
        filter: TransactionDateFilter = TransactionDateFilter(),
    ): List<TransactionDetails> {
        val transactions = findTransactions(userId, type, filter)
        return transactions.mapNotNull { it.toDetails(userId, type) }
    }

    private fun findTransactions(userId: Long, type: TransactionType, filter: TransactionDateFilter): List<Transaction> {
        val whereClauses = mutableListOf("user_id = ?", "type = ?")
        val parameters = mutableListOf<Any>(userId, type.name)

        if (filter.from != null && filter.to != null) {
            whereClauses += "date BETWEEN ? AND ?"
            parameters += filter.from
            parameters += filter.to
        }
        if (filter.categoryIds.isNotEmpty()) {
            whereClauses += "category_id IN (${filter.categoryIds.joinToString(",") { "?" }})"
            parameters.addAll(filter.categoryIds)
        }
        if (filter.accountIds.isNotEmpty()) {
            whereClauses += "tracking_account_id IN (${filter.accountIds.joinToString(",") { "?" }})"
            parameters.addAll(filter.accountIds)
        }
        for (token in filter.notesTokens) {
            whereClauses += "LOWER(COALESCE(notes, '')) LIKE ?"
            parameters += "%${token.lowercase()}%"
        }

        val sql = """
            SELECT id,
                   user_id,
                   type,
                   tracking_account_id,
                   category_id,
                   date,
                   amount_minor,
                   default_currency_amount_minor,
                   default_currency,
                   default_currency_conversion_source,
                   default_currency_conversion_transfer_id,
                   notes,
                   metadata,
                   recurring_rule_id,
                   recurring_instance_date,
                   recurring_locked
            FROM transactions
            WHERE ${whereClauses.joinToString(" AND ")}
            ORDER BY date DESC
        """.trimIndent()

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
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
    val accountIds: List<Long> = emptyList(),
    val notesTokens: List<String> = emptyList(),
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
