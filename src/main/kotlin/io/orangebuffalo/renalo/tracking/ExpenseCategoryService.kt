package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

@Singleton
open class ExpenseCategoryService(
    private val expenseCategoryRepository: ExpenseCategoryRepository,
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

    fun listCategories(userId: Long): List<ExpenseCategory> =
        expenseCategoryRepository.findByUserIdOrderByName(userId)

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
}

data class SaveExpenseCategoryRequest(
    val name: String,
)
