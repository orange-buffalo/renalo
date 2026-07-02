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

    fun listCategories(userId: Long): List<IncomeCategory> =
        incomeCategoryRepository.findByUserIdOrderByName(userId)

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

    fun getMergeSummary(userId: Long, categoryId: Long): IncomeCategoryMergeSummary? {
        val sourceCategory = incomeCategoryRepository.findByIdAndUserId(categoryId, userId)
            ?: return null
        val targetCategories = incomeCategoryRepository.findByUserIdOrderByName(userId)
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
        incomeCategoryRepository.findByIdAndUserId(request.targetCategoryId, userId)
            ?: return CategoryMergeResult.INVALID_TARGET

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

data class MergeIncomeCategoryRequest(
    val targetCategoryId: Long,
)

data class IncomeCategoryMergeSummary(
    val sourceCategory: IncomeCategory,
    val incomesCount: Long,
    val targetCategories: List<IncomeCategory>,
)
