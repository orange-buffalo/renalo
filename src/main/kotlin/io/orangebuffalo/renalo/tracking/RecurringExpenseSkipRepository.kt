package io.orangebuffalo.renalo.tracking

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository
import java.time.LocalDate

@JdbcRepository(dialect = Dialect.POSTGRES)
interface RecurringExpenseSkipRepository : CrudRepository<RecurringExpenseSkip, Long> {
    fun findByRecurringRuleIdAndRecurringInstanceDate(
        recurringRuleId: Long,
        recurringInstanceDate: LocalDate,
    ): RecurringExpenseSkip?

    fun findByRecurringRuleId(recurringRuleId: Long): List<RecurringExpenseSkip>
}
