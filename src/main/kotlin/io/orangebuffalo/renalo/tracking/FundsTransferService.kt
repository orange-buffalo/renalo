package io.orangebuffalo.renalo.tracking

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.sql.ResultSet
import java.time.LocalDate
import javax.sql.DataSource

@Singleton
open class FundsTransferService(
    private val fundsTransferRepository: FundsTransferRepository,
    private val trackingAccountRepository: TrackingAccountRepository,
    private val dataSource: DataSource,
) {
    @Transactional(readOnly = true)
    open fun listTransfers(userId: Long, filter: FundsTransferDateFilter = FundsTransferDateFilter()): List<FundsTransferDetails> =
        findTransfers(userId, filter)
            .mapNotNull { it.toDetails(userId) }

    fun findTransfer(userId: Long, transferId: Long): FundsTransferDetails? =
        fundsTransferRepository.findByIdAndUserId(transferId, userId)?.toDetails(userId)

    @Transactional
    open fun createTransfer(userId: Long, request: SaveFundsTransferRequest): SaveFundsTransferResult {
        val transfer = buildTransfer(userId, request) ?: return SaveFundsTransferResult.BadRequest
        return SaveFundsTransferResult.Saved(fundsTransferRepository.save(transfer).toDetails(userId) ?: return SaveFundsTransferResult.BadRequest)
    }

    @Transactional
    open fun updateTransfer(userId: Long, transferId: Long, request: SaveFundsTransferRequest): SaveFundsTransferResult {
        val existingTransfer = fundsTransferRepository.findByIdAndUserId(transferId, userId)
            ?: return SaveFundsTransferResult.NotFound
        val transfer = buildTransfer(userId, request)?.copy(id = existingTransfer.id)
            ?: return SaveFundsTransferResult.BadRequest
        return SaveFundsTransferResult.Saved(fundsTransferRepository.update(transfer).toDetails(userId) ?: return SaveFundsTransferResult.BadRequest)
    }

    @Transactional
    open fun deleteTransfer(userId: Long, transferId: Long): DeleteFundsTransferResult {
        val transfer = fundsTransferRepository.findByIdAndUserId(transferId, userId)
            ?: return DeleteFundsTransferResult.NotFound
        fundsTransferRepository.delete(transfer)
        return DeleteFundsTransferResult.Deleted
    }

    private fun buildTransfer(userId: Long, request: SaveFundsTransferRequest): FundsTransfer? {
        if (request.sourceAccountId == request.targetAccountId || request.sourceAmountMinor <= 0) {
            return null
        }
        val sourceAccount = trackingAccountRepository.findByIdAndUserId(request.sourceAccountId, userId)
            ?: return null
        val targetAccount = trackingAccountRepository.findByIdAndUserId(request.targetAccountId, userId)
            ?: return null
        val targetAmountMinor = if (sourceAccount.currency == targetAccount.currency) {
            request.sourceAmountMinor
        } else {
            request.targetAmountMinor?.takeIf { it > 0 } ?: return null
        }

        return FundsTransfer(
            userId = userId,
            sourceAccountId = sourceAccount.id!!,
            targetAccountId = targetAccount.id!!,
            sourceAmountMinor = request.sourceAmountMinor,
            targetAmountMinor = targetAmountMinor,
            date = request.date,
        )
    }

    private fun findTransfers(userId: Long, filter: FundsTransferDateFilter): List<FundsTransfer> {
        val whereClauses = mutableListOf("user_id = ?")
        val parameters = mutableListOf<Any>(userId)
        if (filter.from != null && filter.to != null) {
            whereClauses += "date BETWEEN ? AND ?"
            parameters += filter.from
            parameters += filter.to
        }
        if (filter.sourceAccountIds.isNotEmpty()) {
            whereClauses += "source_account_id IN (${filter.sourceAccountIds.joinToString(",") { "?" }})"
            parameters.addAll(filter.sourceAccountIds)
        }
        if (filter.targetAccountIds.isNotEmpty()) {
            whereClauses += "target_account_id IN (${filter.targetAccountIds.joinToString(",") { "?" }})"
            parameters.addAll(filter.targetAccountIds)
        }

        val sql = """
            SELECT id, user_id, source_account_id, target_account_id, source_amount_minor, target_amount_minor, date
            FROM funds_transfers
            WHERE ${whereClauses.joinToString(" AND ")}
            ORDER BY date DESC
        """.trimIndent()

        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.toFundsTransfer())
                        }
                    }
                }
            }
        }
    }

    private fun ResultSet.toFundsTransfer() = FundsTransfer(
        id = getLong("id"),
        userId = getLong("user_id"),
        sourceAccountId = getLong("source_account_id"),
        targetAccountId = getLong("target_account_id"),
        sourceAmountMinor = getLong("source_amount_minor"),
        targetAmountMinor = getLong("target_amount_minor"),
        date = getObject("date", LocalDate::class.java),
    )

    private fun FundsTransfer.toDetails(userId: Long): FundsTransferDetails? {
        val sourceAccount = trackingAccountRepository.findByIdAndUserId(sourceAccountId, userId) ?: return null
        val targetAccount = trackingAccountRepository.findByIdAndUserId(targetAccountId, userId) ?: return null
        return FundsTransferDetails(this, sourceAccount, targetAccount)
    }
}

data class FundsTransferDateFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val sourceAccountIds: List<Long> = emptyList(),
    val targetAccountIds: List<Long> = emptyList(),
)

data class FundsTransferDetails(
    val transfer: FundsTransfer,
    val sourceAccount: TrackingAccount,
    val targetAccount: TrackingAccount,
)

data class SaveFundsTransferRequest(
    val sourceAccountId: Long,
    val targetAccountId: Long,
    val sourceAmountMinor: Long,
    val targetAmountMinor: Long? = null,
    val date: LocalDate,
)

sealed interface SaveFundsTransferResult {
    data class Saved(val transfer: FundsTransferDetails) : SaveFundsTransferResult
    data object BadRequest : SaveFundsTransferResult
    data object NotFound : SaveFundsTransferResult
}

sealed interface DeleteFundsTransferResult {
    data object Deleted : DeleteFundsTransferResult
    data object NotFound : DeleteFundsTransferResult
}
