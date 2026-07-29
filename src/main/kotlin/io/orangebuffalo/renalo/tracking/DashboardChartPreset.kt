package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import io.micronaut.data.annotation.TypeDef
import io.micronaut.data.model.DataType

@MappedEntity("dashboard_chart_presets")
data class DashboardChartPreset(
    @field:Id
    @field:GeneratedValue
    var id: Long? = null,
    val userId: Long,
    val name: String,
    val transactionType: TransactionType,
    val categoryFilterMode: DashboardChartFilterMode,
    @field:TypeDef(type = DataType.JSON)
    val categoryIds: List<Long>,
    val accountFilterMode: DashboardChartFilterMode,
    @field:TypeDef(type = DataType.JSON)
    val accountIds: List<Long>,
    val granularity: TransactionTimeSeriesGranularity,
    val isActive: Boolean = false,
)

enum class DashboardChartFilterMode {
    INCLUDE,
    EXCLUDE,
}
