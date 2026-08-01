package io.orangebuffalo.renalo.ai

import io.micronaut.jdbc.DataSourceResolver
import jakarta.inject.Singleton
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.sql.Connection
import javax.sql.DataSource

@Singleton
class AiChatConversationTurnLock(
    dataSource: DataSource,
    dataSourceResolver: DataSourceResolver,
) {
    private val rawDataSource = dataSourceResolver.resolve(dataSource)

    fun <T : Any> withLock(conversationId: Long, action: () -> Flux<T>): Flux<T> = Flux.usingWhen(
        Mono.fromCallable {
            val connection = rawDataSource.connection
            try {
                connection.apply {
                    prepareStatement("SELECT pg_advisory_lock(?)").use { statement ->
                        statement.setLong(1, conversationId)
                        statement.execute()
                    }
                }
            } catch (error: Throwable) {
                connection.close()
                throw error
            }
        }.subscribeOn(Schedulers.boundedElastic()),
        { action() },
        { connection -> connection.release(conversationId) },
        { connection, _ -> connection.release(conversationId) },
        { connection -> connection.release(conversationId) },
    )

    private fun Connection.release(conversationId: Long): Mono<Void> = Mono.fromRunnable<Void> {
        try {
            prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
                statement.setLong(1, conversationId)
                check(statement.executeQuery().use { it.next() && it.getBoolean(1) }) {
                    "AI chat conversation lock was not held"
                }
            }
        } finally {
            close()
        }
    }.subscribeOn(Schedulers.boundedElastic())
}
