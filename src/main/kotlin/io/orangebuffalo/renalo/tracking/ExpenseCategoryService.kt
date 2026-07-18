package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

@Singleton
open class ExpenseCategoryService(
    private val expenseCategoryRepository: ExpenseCategoryRepository,
    private val transactionRepository: TransactionRepository,
) {
    @Transactional
    open fun createDefaultCategoryForUser(userId: Long): ExpenseCategory {
        val categories = expenseCategoryRepository.findByUserIdOrderByName(userId)
        categories.firstOrNull { it.name == "General" }?.let { return it }

        return expenseCategoryRepository.save(
            ExpenseCategory(
                userId = userId,
                name = "General",
            ),
        )
    }

    fun listCategories(userId: Long, includeArchived: Boolean): List<ExpenseCategoryOverview> {
        val categories = if (includeArchived) {
            expenseCategoryRepository.findByUserIdOrderByName(userId)
        } else {
            expenseCategoryRepository.findByUserIdAndArchivedFalseOrderByName(userId)
        }

        val usageByCategoryId = transactionRepository.findCategoryUsage(userId, TransactionType.EXPENSE)
            .associateBy { it.categoryId }

        return categories.sortedWith(
            compareByDescending<ExpenseCategory> { usageByCategoryId[it.id]?.lastUsedDate }
                .thenBy { it.name },
        ).map { category ->
            ExpenseCategoryOverview(category, usageByCategoryId[category.id]?.entriesCount ?: 0)
        }
    }

    fun findCategory(userId: Long, categoryId: Long): ExpenseCategory? =
        expenseCategoryRepository.findByIdAndUserId(categoryId, userId)

    fun createCategory(userId: Long, request: SaveExpenseCategoryRequest): ExpenseCategory? {
        val name = request.name.trim()
        if (name.isBlank()) {
            return null
        }

        return expenseCategoryRepository.save(
            ExpenseCategory(
                userId = userId,
                name = name,
                archived = false,
            ),
        )
    }

    fun updateCategory(userId: Long, categoryId: Long, request: SaveExpenseCategoryRequest): ExpenseCategory? {
        val category = expenseCategoryRepository.findByIdAndUserId(categoryId, userId)
            ?: return null
        val name = request.name.trim()
        if (name.isBlank()) {
            return null
        }

        return expenseCategoryRepository.update(category.copy(name = name))
    }

    fun archiveCategory(userId: Long, categoryId: Long): ExpenseCategory? {
        val category = expenseCategoryRepository.findByIdAndUserId(categoryId, userId)
            ?: return null

        return expenseCategoryRepository.update(category.copy(archived = true))
    }

    fun unarchiveCategory(userId: Long, categoryId: Long): ExpenseCategory? {
        val category = expenseCategoryRepository.findByIdAndUserId(categoryId, userId)
            ?: return null

        return expenseCategoryRepository.update(category.copy(archived = false))
    }

    fun getMergeSummary(userId: Long, categoryId: Long): ExpenseCategoryMergeSummary? {
        val sourceCategory = expenseCategoryRepository.findByIdAndUserId(categoryId, userId)
            ?: return null
        val targetCategories = expenseCategoryRepository.findByUserIdAndArchivedFalseOrderByName(userId)
            .filter { it.id != categoryId }

        return ExpenseCategoryMergeSummary(
            sourceCategory = sourceCategory,
            expensesCount = transactionRepository.countByUserIdAndCategoryIdAndType(
                userId,
                categoryId,
                TransactionType.EXPENSE,
            ),
            targetCategories = targetCategories,
        )
    }

    @Transactional
    open fun mergeCategory(userId: Long, sourceCategoryId: Long, request: MergeExpenseCategoryRequest): CategoryMergeResult {
        expenseCategoryRepository.findByIdAndUserId(sourceCategoryId, userId)
            ?: return CategoryMergeResult.NOT_FOUND
        if (sourceCategoryId == request.targetCategoryId) {
            return CategoryMergeResult.INVALID_TARGET
        }
        val targetCategory = expenseCategoryRepository.findByIdAndUserId(request.targetCategoryId, userId)
            ?: return CategoryMergeResult.INVALID_TARGET
        if (targetCategory.archived) {
            return CategoryMergeResult.INVALID_TARGET
        }

        transactionRepository.reassignCategory(
            userId = userId,
            type = TransactionType.EXPENSE,
            sourceCategoryId = sourceCategoryId,
            targetCategoryId = request.targetCategoryId,
        )
        expenseCategoryRepository.deleteByIdAndUserId(sourceCategoryId, userId)
        return CategoryMergeResult.MERGED
    }
}

data class SaveExpenseCategoryRequest(
    val name: String,
)

data class ExpenseCategoryOverview(
    val category: ExpenseCategory,
    val entriesCount: Long,
)

data class MergeExpenseCategoryRequest(
    val targetCategoryId: Long,
)

data class ExpenseCategoryMergeSummary(
    val sourceCategory: ExpenseCategory,
    val expensesCount: Long,
    val targetCategories: List<ExpenseCategory>,
)

enum class CategoryMergeResult {
    MERGED,
    NOT_FOUND,
    INVALID_TARGET,
}
