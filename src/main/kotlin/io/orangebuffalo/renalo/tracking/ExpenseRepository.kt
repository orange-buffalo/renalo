package io.orangebuffalo.renalo.tracking

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository
import java.time.LocalDate

@JdbcRepository(dialect = Dialect.POSTGRES)
interface ExpenseRepository : CrudRepository<Expense, Long> {
    fun findByUserIdOrderByDateDesc(userId: Long): List<Expense>

    fun findByIdAndUserId(id: Long, userId: Long): Expense?

    fun findByRecurringRuleIdAndRecurringInstanceDate(
        recurringRuleId: Long,
        recurringInstanceDate: LocalDate,
    ): Expense?

    fun findByRecurringRuleIdOrderByRecurringInstanceDate(recurringRuleId: Long): List<Expense>
}
