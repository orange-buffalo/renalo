package io.orangebuffalo.renalo.user

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository
import java.time.Instant

@JdbcRepository(dialect = Dialect.POSTGRES)
interface UserActivationTokenRepository : CrudRepository<UserActivationToken, Long> {
    fun findByUserId(userId: Long): UserActivationToken?

    fun deleteByUserId(userId: Long)

    fun deleteByExpiresAtLessThanEquals(expiresAt: Instant)
}
