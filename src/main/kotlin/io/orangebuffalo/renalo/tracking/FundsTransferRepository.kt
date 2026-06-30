package io.orangebuffalo.renalo.tracking

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository
import java.time.LocalDate

@JdbcRepository(dialect = Dialect.POSTGRES)
interface FundsTransferRepository : CrudRepository<FundsTransfer, Long> {
    fun findByUserIdOrderByDateDesc(userId: Long): List<FundsTransfer>

    fun findByUserIdAndDateBetweenOrderByDateDesc(userId: Long, from: LocalDate, to: LocalDate): List<FundsTransfer>

    fun findByIdAndUserId(id: Long, userId: Long): FundsTransfer?
}
