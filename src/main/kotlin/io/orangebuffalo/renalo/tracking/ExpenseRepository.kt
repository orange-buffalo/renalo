package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.Query
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

    @Query(
        """
            INSERT INTO expenses (
                user_id,
                tracking_account_id,
                category_id,
                date,
                amount_minor,
                notes,
                recurring_rule_id,
                recurring_instance_date,
                recurring_locked
            ) VALUES (
                :userId,
                :trackingAccountId,
                :categoryId,
                :date,
                :amountMinor,
                :notes,
                :recurringRuleId,
                :recurringInstanceDate,
                FALSE
            )
            ON CONFLICT (recurring_rule_id, recurring_instance_date)
                WHERE recurring_rule_id IS NOT NULL
            DO NOTHING
            RETURNING id
        """,
    )
    fun createGeneratedExpenseIfMissing(
        userId: Long,
        trackingAccountId: Long,
        categoryId: Long,
        date: LocalDate,
        amountMinor: Long,
        notes: String?,
        recurringRuleId: Long,
        recurringInstanceDate: LocalDate,
    ): Long?
}
