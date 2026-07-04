package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

@JdbcRepository(dialect = Dialect.POSTGRES)
interface AccountAdjustmentRepository : CrudRepository<AccountAdjustment, Long> {
    fun findByUserIdAndTrackingAccountIdOrderByIdDesc(userId: Long, trackingAccountId: Long): List<AccountAdjustment>

    fun findByIdAndUserId(id: Long, userId: Long): AccountAdjustment?

    fun findByUserId(userId: Long): List<AccountAdjustment>

    @Query(
        """
            SELECT COALESCE(SUM(adjustment_amount_minor), 0)
            FROM account_adjustments
            WHERE user_id = :userId
              AND tracking_account_id = :trackingAccountId
        """,
    )
    fun sumAdjustmentsByUserIdAndTrackingAccountId(userId: Long, trackingAccountId: Long): Long
}
