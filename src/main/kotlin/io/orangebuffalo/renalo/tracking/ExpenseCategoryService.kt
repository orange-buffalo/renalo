package io.orangebuffalo.renalo.tracking

import jakarta.inject.Singleton

@Singleton
class ExpenseCategoryService(
    private val expenseCategoryRepository: ExpenseCategoryRepository,
) {
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
