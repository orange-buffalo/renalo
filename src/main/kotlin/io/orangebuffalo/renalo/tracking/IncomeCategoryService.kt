package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

@Singleton
open class IncomeCategoryService(
    private val incomeCategoryRepository: IncomeCategoryRepository,
    private val transactionRepository: TransactionRepository,
) {
    @Transactional
    open fun createDefaultCategoryForUser(userId: Long): IncomeCategory {
        val categories = incomeCategoryRepository.findByUserIdOrderByName(userId)
        categories.firstOrNull { it.name == "General" }?.let { return it }

        return incomeCategoryRepository.save(
            IncomeCategory(
                userId = userId,
                name = "General",
            ),
        )
    }

    fun listCategories(userId: Long, includeArchived: Boolean): List<IncomeCategoryOverview> {
        val categories = if (includeArchived) {
            incomeCategoryRepository.findByUserIdOrderByName(userId)
        } else {
            incomeCategoryRepository.findByUserIdAndArchivedFalseOrderByName(userId)
        }

        val usageByCategoryId = transactionRepository.findCategoryUsage(userId, TransactionType.INCOME)
            .associateBy { it.categoryId }

        return categories.sortedWith(
            compareByDescending<IncomeCategory> { usageByCategoryId[it.id]?.lastUsedDate }
                .thenBy { it.name },
        ).map { category ->
            IncomeCategoryOverview(category, usageByCategoryId[category.id]?.entriesCount ?: 0)
        }
    }

    fun findCategory(userId: Long, categoryId: Long): IncomeCategory? =
        incomeCategoryRepository.findByIdAndUserId(categoryId, userId)

    fun createCategory(userId: Long, request: SaveIncomeCategoryRequest): IncomeCategory? {
        val name = request.name.trim()
        if (name.isBlank()) {
            return null
        }

        return incomeCategoryRepository.save(
            IncomeCategory(
                userId = userId,
                name = name,
                archived = false,
            ),
        )
    }

    fun updateCategory(userId: Long, categoryId: Long, request: SaveIncomeCategoryRequest): IncomeCategory? {
        val category = incomeCategoryRepository.findByIdAndUserId(categoryId, userId)
            ?: return null
        val name = request.name.trim()
        if (name.isBlank()) {
            return null
        }

        return incomeCategoryRepository.update(category.copy(name = name))
    }

    fun archiveCategory(userId: Long, categoryId: Long): IncomeCategory? {
        val category = incomeCategoryRepository.findByIdAndUserId(categoryId, userId)
            ?: return null

        return incomeCategoryRepository.update(category.copy(archived = true))
    }

    fun unarchiveCategory(userId: Long, categoryId: Long): IncomeCategory? {
        val category = incomeCategoryRepository.findByIdAndUserId(categoryId, userId)
            ?: return null

        return incomeCategoryRepository.update(category.copy(archived = false))
    }

    fun getMergeSummary(userId: Long, categoryId: Long): IncomeCategoryMergeSummary? {
        val sourceCategory = incomeCategoryRepository.findByIdAndUserId(categoryId, userId)
            ?: return null
        val targetCategories = incomeCategoryRepository.findByUserIdAndArchivedFalseOrderByName(userId)
            .filter { it.id != categoryId }

        return IncomeCategoryMergeSummary(
            sourceCategory = sourceCategory,
            incomesCount = transactionRepository.countByUserIdAndCategoryIdAndType(
                userId,
                categoryId,
                TransactionType.INCOME,
            ),
            targetCategories = targetCategories,
        )
    }

    @Transactional
    open fun mergeCategory(userId: Long, sourceCategoryId: Long, request: MergeIncomeCategoryRequest): CategoryMergeResult {
        incomeCategoryRepository.findByIdAndUserId(sourceCategoryId, userId)
            ?: return CategoryMergeResult.NOT_FOUND
        if (sourceCategoryId == request.targetCategoryId) {
            return CategoryMergeResult.INVALID_TARGET
        }
        val targetCategory = incomeCategoryRepository.findByIdAndUserId(request.targetCategoryId, userId)
            ?: return CategoryMergeResult.INVALID_TARGET
        if (targetCategory.archived) {
            return CategoryMergeResult.INVALID_TARGET
        }

        transactionRepository.reassignCategory(
            userId = userId,
            type = TransactionType.INCOME,
            sourceCategoryId = sourceCategoryId,
            targetCategoryId = request.targetCategoryId,
        )
        incomeCategoryRepository.deleteByIdAndUserId(sourceCategoryId, userId)
        return CategoryMergeResult.MERGED
    }
}

data class SaveIncomeCategoryRequest(
    val name: String,
)

data class IncomeCategoryOverview(
    val category: IncomeCategory,
    val entriesCount: Long,
)

data class MergeIncomeCategoryRequest(
    val targetCategoryId: Long,
)

data class IncomeCategoryMergeSummary(
    val sourceCategory: IncomeCategory,
    val incomesCount: Long,
    val targetCategories: List<IncomeCategory>,
)
