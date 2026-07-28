package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

@JdbcRepository(dialect = Dialect.POSTGRES)
interface DashboardChartPresetRepository : CrudRepository<DashboardChartPreset, Long> {
    @Query("SELECT id FROM users WHERE id = :userId FOR UPDATE")
    fun lockUser(userId: Long): Long

    @Query(
        """
            SELECT *
            FROM dashboard_chart_presets
            WHERE user_id = :userId
            ORDER BY transaction_type, name
        """,
    )
    fun findByUserIdOrderByTypeAndName(userId: Long): List<DashboardChartPreset>

    fun findByIdAndUserId(id: Long, userId: Long): DashboardChartPreset?

    @Query(
        """
            UPDATE dashboard_chart_presets
            SET is_active = FALSE
            WHERE user_id = :userId AND transaction_type = :transactionType
        """,
    )
    fun clearActive(userId: Long, transactionType: TransactionType)

    @Query(
        """
            UPDATE dashboard_chart_presets
            SET category_ids = (
                SELECT COALESCE(jsonb_agg(replacement.value), '[]'::jsonb)
                FROM (
                    SELECT DISTINCT CASE
                        WHEN value = to_jsonb(:sourceCategoryId) THEN to_jsonb(:targetCategoryId)
                        ELSE value
                    END AS value
                    FROM jsonb_array_elements(category_ids) value
                ) replacement
            )
            WHERE user_id = :userId
              AND transaction_type = :transactionType
              AND category_ids @> jsonb_build_array(:sourceCategoryId)
        """,
    )
    fun replaceCategoryReference(
        userId: Long,
        transactionType: TransactionType,
        sourceCategoryId: Long,
        targetCategoryId: Long,
    )

    @Query(
        """
            UPDATE dashboard_chart_presets
            SET account_ids = (
                SELECT COALESCE(jsonb_agg(replacement.value), '[]'::jsonb)
                FROM (
                    SELECT DISTINCT CASE
                        WHEN value = to_jsonb(:sourceAccountId) THEN to_jsonb(:targetAccountId)
                        ELSE value
                    END AS value
                    FROM jsonb_array_elements(account_ids) value
                ) replacement
            )
            WHERE user_id = :userId
              AND account_ids @> jsonb_build_array(:sourceAccountId)
        """,
    )
    fun replaceAccountReference(userId: Long, sourceAccountId: Long, targetAccountId: Long)
}
