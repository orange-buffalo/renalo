package io.orangebuffalo.renalo.auth.passkeys

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository
import java.time.Instant

@JdbcRepository(dialect = Dialect.POSTGRES)
interface PasskeyChallengeRepository : CrudRepository<PasskeyChallenge, Long> {
    fun findByRequestId(requestId: String): PasskeyChallenge?

    fun deleteByRequestId(requestId: String)

    fun deleteByExpiresAtLessThanEquals(expiresAt: Instant)
}
