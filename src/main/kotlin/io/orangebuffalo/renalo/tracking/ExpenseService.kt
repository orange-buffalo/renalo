package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.time.LocalDate

@Singleton
open class ExpenseService(
    private val expenseRepository: ExpenseRepository,
    private val trackingAccountRepository: TrackingAccountRepository,
    private val expenseCategoryRepository: ExpenseCategoryRepository,
) {
    fun listExpenses(userId: Long): List<ExpenseDetails> =
        expenseRepository.findByUserIdOrderByDateDesc(userId).mapNotNull { it.toDetails(userId) }

    fun findExpense(userId: Long, expenseId: Long): ExpenseDetails? =
        expenseRepository.findByIdAndUserId(expenseId, userId)?.toDetails(userId)

    @Transactional
    open fun createExpense(userId: Long, request: SaveExpenseRequest): ExpenseDetails? =
        saveExpense(userId, null, request)

    @Transactional
    open fun updateExpense(userId: Long, expenseId: Long, request: SaveExpenseRequest): ExpenseDetails? {
        val existingExpense = expenseRepository.findByIdAndUserId(expenseId, userId)
            ?: return null

        return saveExpense(userId, existingExpense, request)
    }

    @Transactional
    open fun deleteExpense(userId: Long, expenseId: Long): Boolean {
        val expense = expenseRepository.findByIdAndUserId(expenseId, userId)
            ?: return false
        expenseRepository.delete(expense)
        return true
    }

    private fun saveExpense(userId: Long, existingExpense: Expense?, request: SaveExpenseRequest): ExpenseDetails? {
        if (request.amountMinor <= 0) {
            return null
        }
        val account = trackingAccountRepository.findByIdAndUserId(request.trackingAccountId, userId)
            ?: return null
        val category = expenseCategoryRepository.findByIdAndUserId(request.categoryId, userId)
            ?: return null
        val notes = request.notes?.trim()?.takeIf { it.isNotBlank() }
        val expense = Expense(
            id = existingExpense?.id,
            userId = userId,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            date = request.date,
            amountMinor = request.amountMinor,
            notes = notes,
        )

        val savedExpense = if (existingExpense == null) {
            expenseRepository.save(expense)
        } else {
            expenseRepository.update(expense)
        }
        return ExpenseDetails(savedExpense, account, category)
    }

    private fun Expense.toDetails(userId: Long): ExpenseDetails? {
        val account = trackingAccountRepository.findByIdAndUserId(trackingAccountId, userId)
            ?: return null
        val category = expenseCategoryRepository.findByIdAndUserId(categoryId, userId)
            ?: return null
        return ExpenseDetails(this, account, category)
    }
}

data class ExpenseDetails(
    val expense: Expense,
    val account: TrackingAccount,
    val category: ExpenseCategory,
)

data class SaveExpenseRequest(
    val trackingAccountId: Long,
    val categoryId: Long,
    val date: LocalDate,
    val amountMinor: Long,
    val notes: String? = null,
)
