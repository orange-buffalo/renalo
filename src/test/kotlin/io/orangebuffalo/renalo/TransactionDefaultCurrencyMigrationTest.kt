package io.orangebuffalo.renalo

import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.PostgresTestContainer
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class TransactionDefaultCurrencyMigrationTest : IntegrationTestSupport() {
    @Test
    fun upgradesV27ProjectionsWithTransferEvidenceOnceDuringV28Migration() {
        val schema = "transaction_default_currency_migration_test"
        dropSchema(schema)
        try {
            val flywayAtV26 = flyway(schema, MigrationVersion.fromVersion("26"))
            flywayAtV26.migrate()

            val ids = connection().use { connection ->
                connection.createStatement().use { it.execute("SET search_path TO $schema") }
                val userId = connection.insertReturningId(
                    "INSERT INTO users (username, password_hash, type) VALUES ('migration-user', 'hash', 'USER') RETURNING id",
                )
                val defaultAccountId = connection.insertReturningId(
                    """
                        INSERT INTO tracking_accounts (user_id, name, currency, initial_balance_minor, is_default)
                        VALUES ($userId, 'AUD', 'AUD', 0, TRUE)
                        RETURNING id
                    """.trimIndent(),
                )
                val foreignAccountId = connection.insertReturningId(
                    """
                        INSERT INTO tracking_accounts (user_id, name, currency, initial_balance_minor, is_default)
                        VALUES ($userId, 'USD', 'USD', 0, FALSE)
                        RETURNING id
                    """.trimIndent(),
                )
                val sameCurrencyTransactionId = connection.insertReturningId(
                    """
                        INSERT INTO transactions (user_id, type, tracking_account_id, category_id, date, amount_minor)
                        VALUES ($userId, 'EXPENSE', $defaultAccountId, 1, DATE '2026-01-10', 500)
                        RETURNING id
                    """.trimIndent(),
                )
                val foreignTransactionId = connection.insertReturningId(
                    """
                        INSERT INTO transactions (user_id, type, tracking_account_id, category_id, date, amount_minor)
                        VALUES ($userId, 'INCOME', $foreignAccountId, 1, DATE '2026-01-10', 1000)
                        RETURNING id
                    """.trimIndent(),
                )
                connection.createStatement().use {
                    it.executeUpdate(
                        """
                            INSERT INTO transactions (
                                user_id, type, tracking_account_id, category_id, date, amount_minor
                            )
                            SELECT $userId, 'INCOME', $foreignAccountId, 1, DATE '2026-01-10', 1000
                            FROM generate_series(1, 500)
                        """.trimIndent(),
                    )
                }
                val transferId = connection.insertReturningId(
                    """
                        INSERT INTO funds_transfers (
                            user_id, source_account_id, target_account_id, source_amount_minor, target_amount_minor, date
                        )
                        VALUES ($userId, $foreignAccountId, $defaultAccountId, 1000, 1579, DATE '2026-01-12')
                        RETURNING id
                    """.trimIndent(),
                )
                MigrationIds(sameCurrencyTransactionId, foreignTransactionId, transferId)
            }

            flyway(schema, MigrationVersion.fromVersion("27")).migrate().migrationsExecuted.shouldBe(1)
            connection().use { connection ->
                connection.projection(schema, ids.sameCurrencyTransactionId).shouldBe(
                    MigrationProjection(500, "AUD", "SAME_CURRENCY", null),
                )
                connection.projection(schema, ids.foreignTransactionId).shouldBe(
                    MigrationProjection(null, "AUD", "UNAVAILABLE", null),
                )
                connection.countProjections(schema, "UNAVAILABLE").shouldBe(501)
            }

            val currentFlyway = flyway(schema)
            currentFlyway.migrate().migrationsExecuted.shouldBe(1)
            connection().use { connection ->
                connection.projection(schema, ids.sameCurrencyTransactionId).shouldBe(
                    MigrationProjection(500, "AUD", "SAME_CURRENCY", null),
                )
                connection.projection(schema, ids.foreignTransactionId).shouldBe(
                    MigrationProjection(1579, "AUD", "ACTUAL_TRANSFER", ids.transferId),
                )
                connection.countProjections(schema, "ACTUAL_TRANSFER").shouldBe(501)
                connection.createStatement().use {
                    it.executeUpdate(
                        """
                            UPDATE $schema.transactions
                            SET default_currency_amount_minor = NULL,
                                default_currency = NULL,
                                default_currency_conversion_source = 'UNAVAILABLE',
                                default_currency_conversion_transfer_id = NULL
                            WHERE id = ${ids.foreignTransactionId}
                        """.trimIndent(),
                    )
                }
            }

            currentFlyway.migrate().migrationsExecuted.shouldBe(0)
            connection().use { connection ->
                connection.projection(schema, ids.foreignTransactionId).shouldBe(
                    MigrationProjection(null, null, "UNAVAILABLE", null),
                )
            }
        } finally {
            dropSchema(schema)
        }
    }

    private fun flyway(schema: String, target: MigrationVersion? = null): Flyway {
        val configuration = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .defaultSchema(schema)
            .schemas(schema)
            .locations("classpath:db/migration")
        if (target != null) {
            configuration.target(target)
        }
        return configuration.load()
    }

    private fun dropSchema(schema: String) {
        connection().use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private val postgres get() = PostgresTestContainer.getContainer()

    private fun Connection.insertReturningId(sql: String): Long = prepareStatement(sql).use { statement ->
        statement.executeQuery().use { result ->
            result.next()
            result.getLong("id")
        }
    }

    private fun Connection.projection(schema: String, transactionId: Long): MigrationProjection =
        prepareStatement(
            """
                SELECT default_currency_amount_minor,
                       default_currency,
                       default_currency_conversion_source,
                       default_currency_conversion_transfer_id
                FROM $schema.transactions
                WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, transactionId)
            statement.executeQuery().use { result ->
                result.next()
                MigrationProjection(
                    amountMinor = result.getLong("default_currency_amount_minor").takeUnless { result.wasNull() },
                    currency = result.getString("default_currency"),
                    source = result.getString("default_currency_conversion_source"),
                    transferId = result.getLong("default_currency_conversion_transfer_id").takeUnless { result.wasNull() },
                )
            }
        }

    private fun Connection.countProjections(schema: String, source: String): Int =
        prepareStatement(
            "SELECT COUNT(*) AS projection_count FROM $schema.transactions WHERE default_currency_conversion_source = ?",
        ).use { statement ->
            statement.setString(1, source)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt("projection_count")
            }
        }
}

private data class MigrationIds(
    val sameCurrencyTransactionId: Long,
    val foreignTransactionId: Long,
    val transferId: Long,
)

private data class MigrationProjection(
    val amountMinor: Long?,
    val currency: String?,
    val source: String,
    val transferId: Long?,
)
