package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

@JdbcRepository(dialect = Dialect.POSTGRES)
interface TrackingAccountRepository : CrudRepository<TrackingAccount, Long> {
    fun findByUserIdOrderByName(userId: Long): List<TrackingAccount>

    fun findByIdAndUserId(id: Long, userId: Long): TrackingAccount?

    fun countByUserId(userId: Long): Long

    fun deleteByIdAndUserId(id: Long, userId: Long)

    @Query("UPDATE tracking_accounts SET is_default = FALSE WHERE user_id = :userId")
    fun clearDefaultForUser(userId: Long)
}
