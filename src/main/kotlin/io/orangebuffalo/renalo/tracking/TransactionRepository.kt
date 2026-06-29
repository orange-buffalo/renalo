package io.orangebuffalo.renalo.tracking

import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository
import java.time.LocalDate

@JdbcRepository(dialect = Dialect.POSTGRES)
interface TransactionRepository : CrudRepository<Transaction, Long> {
    fun findByUserIdAndTypeOrderByDateDesc(userId: Long, type: TransactionType): List<Transaction>

    fun findByUserIdAndTypeAndDateBetweenOrderByDateDesc(
        userId: Long,
        type: TransactionType,
        from: LocalDate,
        to: LocalDate,
    ): List<Transaction>

    fun findByIdAndUserIdAndType(id: Long, userId: Long, type: TransactionType): Transaction?

    fun findByRecurringRuleIdAndRecurringInstanceDate(
        recurringRuleId: Long,
        recurringInstanceDate: LocalDate,
    ): Transaction?

    fun findByRecurringRuleIdOrderByRecurringInstanceDate(recurringRuleId: Long): List<Transaction>

    @Query(
        """
            INSERT INTO transactions (
                user_id,
                type,
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
                :type,
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
    fun createGeneratedTransactionIfMissing(
        userId: Long,
        type: TransactionType,
        trackingAccountId: Long,
        categoryId: Long,
        date: LocalDate,
        amountMinor: Long,
        notes: String?,
        recurringRuleId: Long,
        recurringInstanceDate: LocalDate,
    ): Long?
}
