package io.orangebuffalo.renalo.tracking

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

@JdbcRepository(dialect = Dialect.POSTGRES)
interface ExpenseCategoryRepository : CrudRepository<ExpenseCategory, Long> {
    fun findByUserIdOrderByName(userId: Long): List<ExpenseCategory>

    fun findByIdAndUserId(id: Long, userId: Long): ExpenseCategory?
}
