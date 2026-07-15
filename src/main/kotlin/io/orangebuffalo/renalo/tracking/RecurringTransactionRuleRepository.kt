package io.orangebuffalo.renalo.tracking

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.annotation.Query
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

@JdbcRepository(dialect = Dialect.POSTGRES)
interface RecurringTransactionRuleRepository : CrudRepository<RecurringTransactionRule, Long> {
    fun findByStatus(status: RecurringTransactionRuleStatus): List<RecurringTransactionRule>

    fun findByUserIdAndTransactionTypeAndStatus(
        userId: Long,
        transactionType: TransactionType,
        status: RecurringTransactionRuleStatus,
    ): List<RecurringTransactionRule>

    fun findByIdAndUserIdAndTransactionType(
        id: Long,
        userId: Long,
        transactionType: TransactionType,
    ): RecurringTransactionRule?

    @Query(
        """
            UPDATE recurring_transaction_rules
            SET tracking_account_id = :targetAccountId
            WHERE user_id = :userId
              AND tracking_account_id = :sourceAccountId
        """,
    )
    fun reassignTrackingAccount(userId: Long, sourceAccountId: Long, targetAccountId: Long)
}
