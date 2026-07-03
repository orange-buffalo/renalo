package io.orangebuffalo.renalo.tracking

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

@JdbcRepository(dialect = Dialect.POSTGRES)
interface IncomeCategoryRepository : CrudRepository<IncomeCategory, Long> {
    fun findByUserIdOrderByName(userId: Long): List<IncomeCategory>

    fun findByUserIdAndArchivedFalseOrderByName(userId: Long): List<IncomeCategory>

    fun findByIdAndUserId(id: Long, userId: Long): IncomeCategory?

    fun deleteByIdAndUserId(id: Long, userId: Long)
}
