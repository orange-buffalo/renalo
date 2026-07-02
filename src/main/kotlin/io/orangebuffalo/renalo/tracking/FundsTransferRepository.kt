package io.orangebuffalo.renalo.tracking

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.annotation.Query
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository
import java.time.LocalDate

@JdbcRepository(dialect = Dialect.POSTGRES)
interface FundsTransferRepository : CrudRepository<FundsTransfer, Long> {
    fun findByUserIdOrderByDateDesc(userId: Long): List<FundsTransfer>

    fun findByUserIdAndDateBetweenOrderByDateDesc(userId: Long, from: LocalDate, to: LocalDate): List<FundsTransfer>

    fun findByIdAndUserId(id: Long, userId: Long): FundsTransfer?

    fun findByUserIdAndDateAndSourceAccountIdAndTargetAccountIdAndSourceAmountMinorAndTargetAmountMinor(
        userId: Long,
        date: LocalDate,
        sourceAccountId: Long,
        targetAccountId: Long,
        sourceAmountMinor: Long,
        targetAmountMinor: Long,
    ): List<FundsTransfer>

    @Query(
        """
            SELECT COUNT(*)
            FROM funds_transfers
            WHERE user_id = :userId
              AND (source_account_id = :accountId OR target_account_id = :accountId)
        """,
    )
    fun countByUserIdAndAccountId(userId: Long, accountId: Long): Long

    @Query(
        """
            DELETE FROM funds_transfers
            WHERE user_id = :userId
              AND (
                (source_account_id = :sourceAccountId AND target_account_id = :targetAccountId)
                OR (source_account_id = :targetAccountId AND target_account_id = :sourceAccountId)
              )
        """,
    )
    fun deleteInternalTransfers(userId: Long, sourceAccountId: Long, targetAccountId: Long)

    @Query(
        """
            UPDATE funds_transfers
            SET source_account_id = :targetAccountId
            WHERE user_id = :userId
              AND source_account_id = :sourceAccountId
        """,
    )
    fun reassignSourceAccount(userId: Long, sourceAccountId: Long, targetAccountId: Long)

    @Query(
        """
            UPDATE funds_transfers
            SET target_account_id = :targetAccountId
            WHERE user_id = :userId
              AND target_account_id = :sourceAccountId
        """,
    )
    fun reassignTargetAccount(userId: Long, sourceAccountId: Long, targetAccountId: Long)
}
