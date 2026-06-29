package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import jakarta.inject.Singleton
import java.time.LocalDate

@Singleton
open class TransactionService(
    private val transactionRepository: TransactionRepository,
    private val trackingAccountRepository: TrackingAccountRepository,
    private val expenseCategoryRepository: ExpenseCategoryRepository,
    private val incomeCategoryRepository: IncomeCategoryRepository,
    private val recurringTransactionRuleRepository: RecurringTransactionRuleRepository,
    private val recurringTransactionSkipRepository: RecurringTransactionSkipRepository,
    private val recurringTransactionGenerationService: RecurringTransactionGenerationService,
) {
    fun listTransactions(
        userId: Long,
        type: TransactionType,
        filter: TransactionDateFilter = TransactionDateFilter(),
    ): List<TransactionDetails> {
        val transactions = if (filter.from != null && filter.to != null) {
            transactionRepository.findByUserIdAndTypeAndDateBetweenOrderByDateDesc(userId, type, filter.from, filter.to)
        } else {
            transactionRepository.findByUserIdAndTypeOrderByDateDesc(userId, type)
        }
        return transactions.mapNotNull { it.toDetails(userId, type) }
    }

    fun findTransaction(userId: Long, type: TransactionType, transactionId: Long): TransactionDetails? =
        transactionRepository.findByIdAndUserIdAndType(transactionId, userId, type)?.toDetails(userId, type)

    @Transactional
    open fun createTransaction(userId: Long, type: TransactionType, request: SaveTransactionRequest): SaveTransactionResult {
        val recurrence = request.recurrence
        return if (recurrence == null) {
            saveTransaction(userId, type, null, request)?.let { SaveTransactionResult.Saved(it) }
                ?: SaveTransactionResult.BadRequest
        } else {
            createRecurringTransaction(userId, type, request, recurrence)
        }
    }

    @Transactional
    open fun updateTransaction(
        userId: Long,
        type: TransactionType,
        transactionId: Long,
        request: SaveTransactionRequest,
    ): SaveTransactionResult {
        val existingTransaction = transactionRepository.findByIdAndUserIdAndType(transactionId, userId, type)
            ?: return SaveTransactionResult.BadRequest

        if (existingTransaction.recurringRuleId != null) {
            return updateRecurringTransaction(userId, type, existingTransaction, request)
        }

        return saveTransaction(userId, type, existingTransaction, request)?.let { SaveTransactionResult.Saved(it) }
            ?: SaveTransactionResult.BadRequest
    }

    @Transactional
    open fun deleteTransaction(
        userId: Long,
        type: TransactionType,
        transactionId: Long,
        request: DeleteTransactionRequest? = null,
    ): DeleteTransactionResult {
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

        recurringTransactionGenerationService.generateForRule(rule)
        val firstTransaction = transactionRepository.findByRecurringRuleIdAndRecurringInstanceDate(rule.id!!, request.date)
            ?: error("Recurring transaction generation did not create the first occurrence for rule ${rule.id}")

        return SaveTransactionResult.Saved(TransactionDetails(firstTransaction, account, category, rule))
    }

    private fun updateRecurringTransaction(
        userId: Long,
        type: TransactionType,
        existingTransaction: Transaction,
        request: SaveTransactionRequest,
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
                SaveTransactionResult.Saved(TransactionDetails(savedTransaction, account, category, rule))
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
                reassignFollowingTransactionsToNewRule(rule.id!!, newRule, instanceDate, request.amountMinor, notes, existingTransaction.id!!)
                recurringTransactionGenerationService.generateForRule(newRule)
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
                transactionRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!)
                    .filter { !it.recurringLocked || it.id == existingTransaction.id }
                    .forEach {
                        transactionRepository.update(
                            it.copy(
                                trackingAccountId = account.id!!,
                                categoryId = category.id,
                                amountMinor = request.amountMinor,
                                notes = notes,
                            ),
                        )
                    }
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
    ) {
        transactionRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(oldRuleId)
            .filter { it.recurringInstanceDate?.let { date -> date >= instanceDate } == true }
            .forEach { transaction ->
                val shouldApplyEditedValues = !transaction.recurringLocked || transaction.id == selectedTransactionId
                transactionRepository.update(
                    transaction.copy(
                        trackingAccountId = if (shouldApplyEditedValues) newRule.trackingAccountId else transaction.trackingAccountId,
                        categoryId = if (shouldApplyEditedValues) newRule.categoryId else transaction.categoryId,
                        amountMinor = if (shouldApplyEditedValues) amountMinor else transaction.amountMinor,
                        notes = if (shouldApplyEditedValues) notes else transaction.notes,
                        recurringRuleId = newRule.id,
                    ),
                )
            }
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
)

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
