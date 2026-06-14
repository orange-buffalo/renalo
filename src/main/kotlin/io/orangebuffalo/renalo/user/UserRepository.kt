package io.orangebuffalo.renalo.user

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

@JdbcRepository(dialect = Dialect.POSTGRES)
interface UserRepository : CrudRepository<User, Long> {
    fun countByType(type: UserType): Long

    fun findByUsername(username: String): User?
}
