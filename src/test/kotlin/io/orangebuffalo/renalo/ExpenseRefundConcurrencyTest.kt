package io.orangebuffalo.renalo

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.PostgresTestContainer
import io.orangebuffalo.renalo.tracking.*
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class ExpenseRefundConcurrencyTest : ExpenseRefundTestSupport() {
    @Inject lateinit var refunds: ExpenseRefundService

    private val search = RefundSearch("AUD", 12000)

    @Test
    fun searchRemainsConsistentWhileExpensesAreEditedAndDeleted() {
        val f = fixture()
        val rule = rule(f)
        val old = expense(f, 6000, today.minusMonths(1), rule)
        expense(f, 6000, today, rule)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit {
                start.await(10, TimeUnit.SECONDS).shouldBe(true)
                repeat(100) { index ->
                    transactions.update(old.copy(amountMinor = if (index % 2 == 0) 5000 else 6000))
                    val single = expense(f)
                    transactions.update(single.copy(amountMinor = 13000))
                    transactions.deleteById(single.id!!)
                }
            }
            val reader = executor.submit {
                start.await(10, TimeUnit.SECONDS).shouldBe(true)
                repeat(50) {
                    refunds.search(f.user.id!!, search, today).forEach { match ->
                        match.payments.sumOf { it.amountMinor }.shouldBe(search.amountMinor)
                        match.payments.map { it.trackingAccount.currency }.distinct().shouldContainExactly("AUD")
                    }
                }
            }
            start.countDown()
            writer.get(30, TimeUnit.SECONDS)
            reader.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentSavesDeleteExactlyOnce() {
        val f = fixture()
        expense(f)
        val request = request(refunds.search(f.user.id!!, search, today).single())
        val executor = Executors.newFixedThreadPool(2)
        lockWrites(f).use { lock ->
            try {
                val saves = (1..2).map { executor.submit<Boolean> { refunds.save(f.user.id!!, request, today) } }
                awaitWaiters(2)
                lock.commit()
                saves.map { it.get(10, TimeUnit.SECONDS) }.sorted().shouldContainExactly(false, true)
                transactions.findByUserIdAndTypeOrderByDateDesc(f.user.id!!, TransactionType.EXPENSE).shouldContainExactly(emptyList())
            } finally {
                lock.rollback()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun queuedGenerationCannotRecreateRefundedSeries() {
        val f = fixture()
        val rule = rule(f)
        expense(f, 5000, today.minusMonths(1), rule)
        expense(f, 7000, today, rule)
        val request = request(refunds.search(f.user.id!!, search, today).single())
        val executor = Executors.newFixedThreadPool(2)
        lockWrites(f).use { lock ->
            try {
                val save = executor.submit<Boolean> { refunds.save(f.user.id!!, request, today) }
                awaitWaiters(1)
                val generate = executor.submit<RecurringTransactionGenerationResult> { generation.generateForRule(rule, today.plusYears(1)) }
                awaitWaiters(2)
                lock.commit()
                save.get(10, TimeUnit.SECONDS).shouldBe(true)
                generate.get(10, TimeUnit.SECONDS).createdTransactions.shouldBe(0)
                transactions.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!).shouldContainExactly(emptyList())
                rules.findById(rule.id!!).get().status.shouldBe(RecurringTransactionRuleStatus.DELETED)
            } finally {
                lock.rollback()
                executor.shutdownNow()
            }
        }
    }

    private fun request(match: ExpenseRefundMatch) = SaveExpenseRefund(search, match.transactionId, match.kind, match.fingerprint)

    private fun connection(): Connection = PostgresTestContainer.getContainer().let {
        DriverManager.getConnection(it.jdbcUrl, it.username, it.password)
    }

    private fun lockWrites(f: RefundFixture): Connection = connection().apply {
        autoCommit = false
        prepareStatement("SELECT pg_advisory_xact_lock(?)").use {
            it.setLong(1, f.user.id!! xor Long.MIN_VALUE)
            it.execute()
        }
    }

    private fun awaitWaiters(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waiting = connection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted").use {
                        it.next()
                        it.getInt(1)
                    }
                }
            }
            if (waiting == count) return
            Thread.sleep(25)
        }
        error("Timed out waiting for $count refund write-lock waiters")
    }
}
