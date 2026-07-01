package io.orangebuffalo.renalo.auth.passkeys

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

@JdbcRepository(dialect = Dialect.POSTGRES)
interface PasskeyCredentialRepository : CrudRepository<PasskeyCredential, Long> {
    fun findByUserId(userId: Long): List<PasskeyCredential>

    fun findByCredentialId(credentialId: String): PasskeyCredential?

    fun findByUserHandle(userHandle: String): List<PasskeyCredential>

    fun findByCredentialIdAndUserHandle(credentialId: String, userHandle: String): PasskeyCredential?

    fun deleteByIdAndUserId(id: Long, userId: Long)
}
