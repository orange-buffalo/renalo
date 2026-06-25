package io.orangebuffalo.renalo.tracking

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

@JdbcRepository(dialect = Dialect.POSTGRES)
interface ExpenseRepository : CrudRepository<Expense, Long> {
    fun findByUserIdOrderByDateDesc(userId: Long): List<Expense>

    fun findByIdAndUserId(id: Long, userId: Long): Expense?
}
