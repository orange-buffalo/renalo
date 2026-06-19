package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

@Singleton
open class IncomeCategoryService(
    private val incomeCategoryRepository: IncomeCategoryRepository,
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
}

data class SaveIncomeCategoryRequest(
    val name: String,
)
