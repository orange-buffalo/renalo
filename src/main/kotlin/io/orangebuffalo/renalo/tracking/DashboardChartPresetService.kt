package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

@Singleton
open class DashboardChartPresetService(
    private val presetRepository: DashboardChartPresetRepository,
    private val trackingAccountRepository: TrackingAccountRepository,
    private val expenseCategoryRepository: ExpenseCategoryRepository,
    private val incomeCategoryRepository: IncomeCategoryRepository,
) {
    fun listPresets(userId: Long): List<DashboardChartPreset> =
        presetRepository.findByUserIdOrderByTypeAndName(userId)

    @Transactional
    open fun createPreset(
        userId: Long,
        transactionType: TransactionType,
        request: SaveDashboardChartPresetRequest,
    ): SaveDashboardChartPresetResult {
        val preset = request.toPreset(userId, transactionType) ?: return SaveDashboardChartPresetResult.BadRequest
        presetRepository.lockUser(userId)
        presetRepository.clearActive(userId, transactionType)
        return SaveDashboardChartPresetResult.Saved(presetRepository.save(preset.copy(isActive = true)))
    }

    @Transactional
    open fun updatePreset(
        userId: Long,
        transactionType: TransactionType,
        presetId: Long,
        request: SaveDashboardChartPresetRequest,
    ): SaveDashboardChartPresetResult {
        presetRepository.lockUser(userId)
        val existing = presetRepository.findByIdAndUserId(presetId, userId)
            ?.takeIf { it.transactionType == transactionType }
            ?: return SaveDashboardChartPresetResult.NotFound
        val preset = request.toPreset(userId, transactionType, existing)
            ?: return SaveDashboardChartPresetResult.BadRequest
        return SaveDashboardChartPresetResult.Saved(presetRepository.update(preset))
    }

    @Transactional
    open fun setActivePreset(
        userId: Long,
        transactionType: TransactionType,
        presetId: Long?,
    ): SetActiveDashboardChartPresetResult {
        presetRepository.lockUser(userId)
        val preset = presetId?.let { id ->
            presetRepository.findByIdAndUserId(id, userId)
                ?.takeIf { it.transactionType == transactionType }
                ?: return SetActiveDashboardChartPresetResult.NotFound
        }
        presetRepository.clearActive(userId, transactionType)
        if (preset != null) {
            presetRepository.update(preset.copy(isActive = true))
        }
        return SetActiveDashboardChartPresetResult.Updated
    }

    @Transactional
    open fun deletePreset(
        userId: Long,
        transactionType: TransactionType,
        presetId: Long,
    ): DeleteDashboardChartPresetResult {
        presetRepository.lockUser(userId)
        val preset = presetRepository.findByIdAndUserId(presetId, userId)
            ?.takeIf { it.transactionType == transactionType }
            ?: return DeleteDashboardChartPresetResult.NotFound
        presetRepository.delete(preset)
        return DeleteDashboardChartPresetResult.Deleted
    }

    private fun SaveDashboardChartPresetRequest.toPreset(
        userId: Long,
        transactionType: TransactionType,
        existing: DashboardChartPreset? = null,
    ): DashboardChartPreset? {
        val normalizedName = name.trim().takeIf { it.isNotEmpty() && it.length <= 100 } ?: return null
        val normalizedCategoryIds = categoryIds.distinct()
        val normalizedAccountIds = accountIds.distinct()
        if (normalizedCategoryIds.any { it <= 0 } || normalizedAccountIds.any { it <= 0 }) {
            return null
        }
        if (normalizedAccountIds.any { trackingAccountRepository.findByIdAndUserId(it, userId) == null }) {
            return null
        }
        val categoriesAreValid = when (transactionType) {
            TransactionType.EXPENSE -> normalizedCategoryIds.all {
                expenseCategoryRepository.findByIdAndUserId(it, userId) != null
            }
            TransactionType.INCOME -> normalizedCategoryIds.all {
                incomeCategoryRepository.findByIdAndUserId(it, userId) != null
            }
        }
        if (!categoriesAreValid) {
            return null
        }
        return DashboardChartPreset(
            id = existing?.id,
            userId = userId,
            name = normalizedName,
            transactionType = transactionType,
            categoryFilterMode = categoryFilterMode,
            categoryIds = normalizedCategoryIds,
            accountFilterMode = accountFilterMode,
            accountIds = normalizedAccountIds,
            granularity = granularity,
            isActive = existing?.isActive ?: false,
        )
    }
}

data class SaveDashboardChartPresetRequest(
    val name: String,
    val categoryFilterMode: DashboardChartFilterMode,
    val categoryIds: List<Long>,
    val accountFilterMode: DashboardChartFilterMode,
    val accountIds: List<Long>,
    val granularity: TransactionTimeSeriesGranularity,
)

sealed interface SaveDashboardChartPresetResult {
    data class Saved(val preset: DashboardChartPreset) : SaveDashboardChartPresetResult
    data object NotFound : SaveDashboardChartPresetResult
    data object BadRequest : SaveDashboardChartPresetResult
}

enum class SetActiveDashboardChartPresetResult {
    Updated,
    NotFound,
}

enum class DeleteDashboardChartPresetResult {
    Deleted,
    NotFound,
}
