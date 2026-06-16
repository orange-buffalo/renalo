package io.orangebuffalo.renalo.user

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.annotation.Query
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

@JdbcRepository(dialect = Dialect.POSTGRES)
interface UserRepository : CrudRepository<User, Long> {
    fun countByType(type: UserType): Long

    fun findByUsername(username: String): User?

    @Query("SELECT COUNT(*) FROM users")
    fun countAllUsers(): Long

    @Query("SELECT id, username, password_hash, type, active FROM users ORDER BY username LIMIT :limit OFFSET :offset")
    fun findUsersPage(limit: Int, offset: Long): List<User>
}
