package io.orangebuffalo.renalo.user

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository
import java.time.Instant

@JdbcRepository(dialect = Dialect.POSTGRES)
interface UserActivationTokenRepository : CrudRepository<UserActivationToken, Long> {
    fun findByUserId(userId: Long): UserActivationToken?

    fun findByToken(token: String): UserActivationToken?

    fun deleteByUserId(userId: Long)

    fun deleteByToken(token: String)

    fun deleteByExpiresAtLessThanEquals(expiresAt: Instant)
}
