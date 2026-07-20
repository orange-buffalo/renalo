package io.orangebuffalo.renalo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.PostgresTestContainer
import io.orangebuffalo.renalo.tracking.DefaultCurrencyConversionSource
import io.orangebuffalo.renalo.tracking.FundsTransfer
import io.orangebuffalo.renalo.tracking.FundsTransferRepository
import io.orangebuffalo.renalo.tracking.FundsTransferService
import io.orangebuffalo.renalo.tracking.SaveFundsTransferRequest
import io.orangebuffalo.renalo.tracking.SaveFundsTransferResult
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.tracking.Transaction
import io.orangebuffalo.renalo.tracking.TransactionDefaultCurrencyService
import io.orangebuffalo.renalo.tracking.TransactionRepository
import io.orangebuffalo.renalo.tracking.TransactionType
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class TransactionDefaultCurrencyServiceTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var fundsTransferRepository: FundsTransferRepository

    @Inject
    lateinit var transactionDefaultCurrencyService: TransactionDefaultCurrencyService

    @Inject
    lateinit var fundsTransferService: FundsTransferService

    @Test
    fun projectsSameCurrencyAndLeavesMissingOrIndirectEvidenceUnavailable() {
        val user = saveUser()
        val audAccount = saveAccount(user, "AUD", "AUD", isDefault = true)
        val usdAccount = saveAccount(user, "USD", "USD")
        val eurAccount = saveAccount(user, "EUR", "EUR")
        val audExpense = saveTransaction(user, audAccount, TransactionType.EXPENSE, "2026-01-10", 987)
        val usdIncome = saveTransaction(user, usdAccount, TransactionType.INCOME, "2026-01-10", 1_000)
        saveTransfer(user, usdAccount, eurAccount, "2026-01-11", 1_000, 900)
        saveTransfer(user, eurAccount, audAccount, "2026-01-11", 900, 1_500)

        transactionDefaultCurrencyService.recalculateTransactions(user.id!!, listOf(audExpense.id!!, usdIncome.id!!))

        projection(audExpense).shouldBe(
            Projection(987, "AUD", DefaultCurrencyConversionSource.SAME_CURRENCY, null),
        )
        projection(usdIncome).shouldBe(
            Projection(null, "AUD", DefaultCurrencyConversionSource.UNAVAILABLE, null),
        )
    }

    @Test
    fun usesTheExactLaterConversionForForeignIncome() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val foreignAccount = saveAccount(user, "USD", "USD")
        val income = saveTransaction(user, foreignAccount, TransactionType.INCOME, "2026-01-10", 12_345)
        val conversion = saveTransfer(user, foreignAccount, defaultAccount, "2026-01-12", 12_345, 19_876)

        transactionDefaultCurrencyService.recalculateTransaction(user.id!!, income.id!!)

        projection(income).shouldBe(
            Projection(19_876, "AUD", DefaultCurrencyConversionSource.ACTUAL_TRANSFER, conversion.id),
        )
    }

    @Test
    fun ranksExpectedDirectionAndDateSideBeforeAllOtherEvidence() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val foreignAccount = saveAccount(user, "USD", "USD")
        val income = saveTransaction(user, foreignAccount, TransactionType.INCOME, "2026-02-10", 100)
        saveTransfer(user, defaultAccount, foreignAccount, "2026-02-10", 900, 100)
        saveTransfer(user, foreignAccount, defaultAccount, "2026-02-09", 100, 800)
        val expected = saveTransfer(user, foreignAccount, defaultAccount, "2026-02-20", 200, 600)

        transactionDefaultCurrencyService.recalculateTransaction(user.id!!, income.id!!)

        projection(income).transferId.shouldBe(expected.id)
        projection(income).amountMinor.shouldBe(300)
    }

    @Test
    fun ranksExpectedDirectionOnTheWrongDateSideBeforeOppositeDirection() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val foreignAccount = saveAccount(user, "USD", "USD")
        val income = saveTransaction(user, foreignAccount, TransactionType.INCOME, "2026-02-10", 100)
        val expectedDirection = saveTransfer(user, foreignAccount, defaultAccount, "2026-02-09", 100, 700)
        saveTransfer(user, defaultAccount, foreignAccount, "2026-02-11", 900, 100)

        transactionDefaultCurrencyService.recalculateTransaction(user.id!!, income.id!!)

        projection(income).transferId.shouldBe(expectedDirection.id)
    }

    @Test
    fun prefersExpenseFundingBeforeTheExpense() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val foreignAccount = saveAccount(user, "USD", "USD")
        val expense = saveTransaction(user, foreignAccount, TransactionType.EXPENSE, "2026-02-10", 3_000)
        saveTransfer(user, foreignAccount, defaultAccount, "2026-02-11", 3_000, 9_000)
        val funding = saveTransfer(user, defaultAccount, foreignAccount, "2026-02-09", 6_000, 3_000)

        transactionDefaultCurrencyService.recalculateTransaction(user.id!!, expense.id!!)

        projection(expense).shouldBe(
            Projection(6_000, "AUD", DefaultCurrencyConversionSource.ACTUAL_TRANSFER, funding.id),
        )
    }

    @Test
    fun ranksSameForeignAccountBeforeExactAmountAndDate() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val transactionAccount = saveAccount(user, "USD Wallet", "USD")
        val otherUsdAccount = saveAccount(user, "Other USD", "USD")
        val income = saveTransaction(user, transactionAccount, TransactionType.INCOME, "2026-02-10", 100)
        saveTransfer(user, otherUsdAccount, defaultAccount, "2026-02-10", 100, 1_000)
        val sameAccount = saveTransfer(user, transactionAccount, defaultAccount, "2026-02-20", 200, 600)

        transactionDefaultCurrencyService.recalculateTransaction(user.id!!, income.id!!)

        projection(income).transferId.shouldBe(sameAccount.id)
        projection(income).amountMinor.shouldBe(300)
    }

    @Test
    fun ranksExactForeignAmountBeforeDateProximity() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val foreignAccount = saveAccount(user, "USD", "USD")
        val income = saveTransaction(user, foreignAccount, TransactionType.INCOME, "2026-02-10", 100)
        saveTransfer(user, foreignAccount, defaultAccount, "2026-02-10", 200, 600)
        val exactAmount = saveTransfer(user, foreignAccount, defaultAccount, "2026-02-20", 100, 400)

        transactionDefaultCurrencyService.recalculateTransaction(user.id!!, income.id!!)

        projection(income).transferId.shouldBe(exactAmount.id)
    }

    @Test
    fun ranksNearestDateAndThenLowestTransferId() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val foreignAccount = saveAccount(user, "USD", "USD")
        val income = saveTransaction(user, foreignAccount, TransactionType.INCOME, "2026-02-10", 100)
        saveTransfer(user, foreignAccount, defaultAccount, "2026-02-20", 100, 900)
        val lowestIdOnNearestDate = saveTransfer(user, foreignAccount, defaultAccount, "2026-02-12", 100, 500)
        saveTransfer(user, foreignAccount, defaultAccount, "2026-02-12", 100, 600)

        transactionDefaultCurrencyService.recalculateTransaction(user.id!!, income.id!!)

        projection(income).transferId.shouldBe(lowestIdOnNearestDate.id)
    }

    @Test
    fun roundsPositiveValuesToTheNearestDefaultMinorUnitWithHalfUpTies() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val usdAccount = saveAccount(user, "USD", "USD")
        val eurAccount = saveAccount(user, "EUR", "EUR")
        val gbpAccount = saveAccount(user, "GBP", "GBP")
        val half = saveTransaction(user, usdAccount, TransactionType.INCOME, "2026-03-01", 1)
        val belowHalf = saveTransaction(user, eurAccount, TransactionType.INCOME, "2026-03-01", 1)
        val aboveHalf = saveTransaction(user, gbpAccount, TransactionType.INCOME, "2026-03-01", 2)
        saveTransfer(user, usdAccount, defaultAccount, "2026-03-02", 2, 1)
        saveTransfer(user, eurAccount, defaultAccount, "2026-03-02", 100, 49)
        saveTransfer(user, gbpAccount, defaultAccount, "2026-03-02", 3, 1)

        transactionDefaultCurrencyService.recalculateTransactions(
            user.id!!,
            listOf(half.id!!, belowHalf.id!!, aboveHalf.id!!),
        )

        projection(half).amountMinor.shouldBe(1)
        projection(belowHalf).amountMinor.shouldBe(0)
        projection(aboveHalf).amountMinor.shouldBe(1)
    }

    @Test
    fun restatesTheWholeHistoryWhenTheDefaultCurrencyChanges() {
        val user = saveUser()
        val audAccount = saveAccount(user, "AUD", "AUD", isDefault = true)
        val usdAccount = saveAccount(user, "USD", "USD")
        val audIncome = saveTransaction(user, audAccount, TransactionType.INCOME, "2026-04-01", 500)
        val usdIncome = saveTransaction(user, usdAccount, TransactionType.INCOME, "2026-04-01", 1_000)
        val transfer = saveTransfer(user, usdAccount, audAccount, "2026-04-02", 1_000, 1_500)
        transactionDefaultCurrencyService.recalculateForUser(user.id!!)

        trackingAccountRepository.clearDefaultForUser(user.id!!)
        trackingAccountRepository.update(usdAccount.copy(isDefault = true))
        transactionDefaultCurrencyService.recalculateForUser(user.id!!)

        projection(audIncome).shouldBe(
            Projection(333, "USD", DefaultCurrencyConversionSource.ACTUAL_TRANSFER, transfer.id),
        )
        projection(usdIncome).shouldBe(
            Projection(1_000, "USD", DefaultCurrencyConversionSource.SAME_CURRENCY, null),
        )
    }

    @Test
    fun scopesTransactionBatchesAndChunksLargeIdCollections() {
        val user = saveUser()
        val account = saveAccount(user, "Main", "AUD", isDefault = true)
        val transactions = transactionRepository.saveAll(
            (1..502).map { index ->
                newTransaction(user, account, TransactionType.EXPENSE, "2026-05-01", index.toLong())
            },
        ).toList()

        transactionDefaultCurrencyService.recalculateTransactions(user.id!!, transactions.take(501).map { it.id!! })

        val persisted = transactionRepository.findByUserIdAndTypeOrderByDateDesc(user.id!!, TransactionType.EXPENSE)
        persisted.filter { it.defaultCurrencyConversionSource == DefaultCurrencyConversionSource.SAME_CURRENCY }
            .shouldHaveSize(501)
        projection(transactions.last()).shouldBe(
            Projection(null, null, DefaultCurrencyConversionSource.UNAVAILABLE, null),
        )
    }

    @Test
    fun batchesFullHistoryRecalculationAcrossMoreThanFiveHundredTransactions() {
        val user = saveUser()
        val account = saveAccount(user, "Main", "AUD", isDefault = true)
        transactionRepository.saveAll(
            (1..501).map { index ->
                newTransaction(user, account, TransactionType.INCOME, "2026-05-01", index.toLong())
            },
        )

        transactionDefaultCurrencyService.recalculateForUser(user.id!!)

        transactionRepository.findByUserIdAndTypeOrderByDateDesc(user.id!!, TransactionType.INCOME)
            .filter { it.defaultCurrencyConversionSource == DefaultCurrencyConversionSource.SAME_CURRENCY }
            .shouldHaveSize(501)
    }

    @Test
    fun scopesTransactionRecalculationToTheOwningUser() {
        val alice = saveUser("alice")
        val bob = saveUser("bob")
        val aliceAccount = saveAccount(alice, "Alice", "AUD", isDefault = true)
        saveAccount(bob, "Bob", "AUD", isDefault = true)
        val aliceTransaction = saveTransaction(alice, aliceAccount, TransactionType.INCOME, "2026-05-01", 100)

        transactionDefaultCurrencyService.recalculateTransaction(bob.id!!, aliceTransaction.id!!)

        projection(aliceTransaction).shouldBe(
            Projection(null, null, DefaultCurrencyConversionSource.UNAVAILABLE, null),
        )
    }

    @Test
    fun changedTransfersRecalculateOnlyTheirForeignCurrency() {
        val user = saveUser()
        val audAccount = saveAccount(user, "AUD", "AUD", isDefault = true)
        val usdAccount = saveAccount(user, "USD", "USD")
        val eurAccount = saveAccount(user, "EUR", "EUR")
        val audIncome = saveTransaction(user, audAccount, TransactionType.INCOME, "2026-06-01", 100)
        val usdIncomes = transactionRepository.saveAll(
            (1..501).map {
                newTransaction(user, usdAccount, TransactionType.INCOME, "2026-06-01", 100)
            },
        ).toList()
        val eurIncome = saveTransaction(user, eurAccount, TransactionType.INCOME, "2026-06-01", 100)
        val usdTransfer = saveTransfer(user, usdAccount, audAccount, "2026-06-02", 100, 150)

        transactionDefaultCurrencyService.recalculateForChangedTransfers(user.id!!, listOf(usdTransfer))

        usdIncomes.filter { projection(it).amountMinor == 150L }.shouldHaveSize(501)
        projection(audIncome).currency.shouldBe(null)
        projection(eurIncome).currency.shouldBe(null)
    }

    @Test
    fun transfersOutsideTheDefaultCurrencyDoNotTouchAnyProjection() {
        val user = saveUser()
        saveAccount(user, "AUD", "AUD", isDefault = true)
        val usdAccount = saveAccount(user, "USD", "USD")
        val eurAccount = saveAccount(user, "EUR", "EUR")
        val usdIncome = saveTransaction(user, usdAccount, TransactionType.INCOME, "2026-06-01", 100)
        val transfer = saveTransfer(user, usdAccount, eurAccount, "2026-06-02", 100, 90)

        transactionDefaultCurrencyService.recalculateForChangedTransfers(user.id!!, listOf(transfer))

        projection(usdIncome).shouldBe(
            Projection(null, null, DefaultCurrencyConversionSource.UNAVAILABLE, null),
        )
    }

    @Test
    fun rejectsConvertedAmountsThatOverflowLong() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val foreignAccount = saveAccount(user, "USD", "USD")
        val income = saveTransaction(user, foreignAccount, TransactionType.INCOME, "2026-07-01", Long.MAX_VALUE)
        saveTransfer(user, foreignAccount, defaultAccount, "2026-07-02", 1, Long.MAX_VALUE)

        shouldThrow<ArithmeticException> {
            transactionDefaultCurrencyService.recalculateTransaction(user.id!!, income.id!!)
        }
    }

    @Test
    fun rollsBackTheTriggeringTransferWhenProjectionCalculationFails() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val foreignAccount = saveAccount(user, "USD", "USD")
        saveTransaction(user, foreignAccount, TransactionType.INCOME, "2026-07-01", Long.MAX_VALUE)

        shouldThrow<ArithmeticException> {
            fundsTransferService.createTransfer(
                user.id!!,
                SaveFundsTransferRequest(
                    sourceAccountId = foreignAccount.id!!,
                    targetAccountId = defaultAccount.id!!,
                    sourceAmountMinor = 1,
                    targetAmountMinor = Long.MAX_VALUE,
                    date = LocalDate.parse("2026-07-02"),
                ),
            )
        }

        fundsTransferRepository.findByUserIdOrderByDateDesc(user.id!!).shouldHaveSize(0)
    }

    @Test
    fun serializesOverlappingTransferRecalculationsForTheSameUser() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val foreignAccount = saveAccount(user, "USD", "USD")
        val income = saveTransaction(user, foreignAccount, TransactionType.INCOME, "2026-08-01", 100)
        val executor = Executors.newFixedThreadPool(2)
        val postgres = PostgresTestContainer.getContainer()
        val lockConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        lockConnection.autoCommit = false
        lockConnection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, user.id!! xor Long.MIN_VALUE)
            statement.execute()
        }

        try {
            val expectedTransferFuture = executor.submit<SaveFundsTransferResult> {
                fundsTransferService.createTransfer(
                    user.id!!,
                    SaveFundsTransferRequest(
                        sourceAccountId = foreignAccount.id!!,
                        targetAccountId = defaultAccount.id!!,
                        sourceAmountMinor = 100,
                        targetAmountMinor = 200,
                        date = LocalDate.parse("2026-08-02"),
                    ),
                )
            }
            val lessRelevantTransferFuture = executor.submit<SaveFundsTransferResult> {
                fundsTransferService.createTransfer(
                    user.id!!,
                    SaveFundsTransferRequest(
                        sourceAccountId = foreignAccount.id!!,
                        targetAccountId = defaultAccount.id!!,
                        sourceAmountMinor = 100,
                        targetAmountMinor = 900,
                        date = LocalDate.parse("2026-08-20"),
                    ),
                )
            }
            awaitAdvisoryLockWaiters(2)

            lockConnection.commit()
            val expectedTransfer = (expectedTransferFuture.get(10, TimeUnit.SECONDS) as SaveFundsTransferResult.Saved)
                .transfer.transfer
            lessRelevantTransferFuture.get(10, TimeUnit.SECONDS)

            projection(income).shouldBe(
                Projection(200, "AUD", DefaultCurrencyConversionSource.ACTUAL_TRANSFER, expectedTransfer.id),
            )
        } finally {
            lockConnection.rollback()
            lockConnection.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun serializesConcurrentTransferUpdatesBeforeReadingTheirOldInvalidationScope() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val usdAccount = saveAccount(user, "USD", "USD")
        val eurAccount = saveAccount(user, "EUR", "EUR")
        val gbpAccount = saveAccount(user, "GBP", "GBP")
        val transactionsByAccountId = listOf(usdAccount, eurAccount, gbpAccount).associate { account ->
            account.id!! to saveTransaction(user, account, TransactionType.INCOME, "2026-08-01", 100)
        }
        val initialTransfer = (fundsTransferService.createTransfer(
            user.id!!,
            SaveFundsTransferRequest(
                sourceAccountId = usdAccount.id!!,
                targetAccountId = defaultAccount.id!!,
                sourceAmountMinor = 100,
                targetAmountMinor = 150,
                date = LocalDate.parse("2026-08-02"),
            ),
        ) as SaveFundsTransferResult.Saved).transfer.transfer
        val executor = Executors.newFixedThreadPool(2)
        val postgres = PostgresTestContainer.getContainer()
        val lockConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        lockConnection.autoCommit = false
        lockConnection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, user.id!! xor Long.MIN_VALUE)
            statement.execute()
        }

        try {
            val updateFutures = listOf(eurAccount to 200L, gbpAccount to 300L).map { (sourceAccount, targetAmount) ->
                executor.submit<SaveFundsTransferResult> {
                    fundsTransferService.updateTransfer(
                        user.id!!,
                        initialTransfer.id!!,
                        SaveFundsTransferRequest(
                            sourceAccountId = sourceAccount.id!!,
                            targetAccountId = defaultAccount.id!!,
                            sourceAmountMinor = 100,
                            targetAmountMinor = targetAmount,
                            date = LocalDate.parse("2026-08-02"),
                        ),
                    )
                }
            }
            awaitAdvisoryLockWaiters(2)

            lockConnection.commit()
            updateFutures.forEach { it.get(10, TimeUnit.SECONDS).shouldBeInstanceOfSavedTransfer() }

            val finalTransfer = fundsTransferRepository.findById(initialTransfer.id!!).orElseThrow()
            transactionsByAccountId.forEach { (accountId, transaction) ->
                val savedProjection = projection(transaction)
                if (accountId == finalTransfer.sourceAccountId) {
                    savedProjection.source.shouldBe(DefaultCurrencyConversionSource.ACTUAL_TRANSFER)
                    savedProjection.transferId.shouldBe(finalTransfer.id)
                } else {
                    savedProjection.source.shouldBe(DefaultCurrencyConversionSource.UNAVAILABLE)
                    savedProjection.transferId.shouldBe(null)
                }
            }
        } finally {
            lockConnection.rollback()
            lockConnection.close()
            executor.shutdownNow()
        }
    }

    private fun SaveFundsTransferResult.shouldBeInstanceOfSavedTransfer() {
        (this is SaveFundsTransferResult.Saved).shouldBe(true)
    }

    private fun awaitAdvisoryLockWaiters(expectedCount: Int) {
        val postgres = PostgresTestContainer.getContainer()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waitingCount = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT COUNT(*) AS waiting_count FROM pg_locks WHERE locktype = 'advisory' AND NOT granted",
                    ).use { result ->
                        result.next()
                        result.getInt("waiting_count")
                    }
                }
            }
            if (waitingCount >= expectedCount) {
                return
            }
            Thread.sleep(25)
        }
        error("Timed out waiting for $expectedCount valuation lock waiters")
    }

    private fun projection(transaction: Transaction): Projection = transactionRepository.findById(transaction.id!!).get().let {
        Projection(
            amountMinor = it.defaultCurrencyAmountMinor,
            currency = it.defaultCurrency,
            source = it.defaultCurrencyConversionSource,
            transferId = it.defaultCurrencyConversionTransferId,
        )
    }

    private fun saveUser(username: String = "alice"): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = UserType.USER,
        ),
    )

    private fun saveAccount(
        user: User,
        name: String,
        currency: String,
        isDefault: Boolean = false,
    ): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = currency,
            initialBalanceMinor = 0,
            isDefault = isDefault,
        ),
    )

    private fun saveTransaction(
        user: User,
        account: TrackingAccount,
        type: TransactionType,
        date: String,
        amountMinor: Long,
    ): Transaction = transactionRepository.save(newTransaction(user, account, type, date, amountMinor))

    private fun newTransaction(
        user: User,
        account: TrackingAccount,
        type: TransactionType,
        date: String,
        amountMinor: Long,
    ) = Transaction(
        userId = user.id!!,
        type = type,
        trackingAccountId = account.id!!,
        categoryId = 1,
        date = LocalDate.parse(date),
        amountMinor = amountMinor,
    )

    private fun saveTransfer(
        user: User,
        sourceAccount: TrackingAccount,
        targetAccount: TrackingAccount,
        date: String,
        sourceAmountMinor: Long,
        targetAmountMinor: Long,
    ): FundsTransfer = fundsTransferRepository.save(
        FundsTransfer(
            userId = user.id!!,
            sourceAccountId = sourceAccount.id!!,
            targetAccountId = targetAccount.id!!,
            sourceAmountMinor = sourceAmountMinor,
            targetAmountMinor = targetAmountMinor,
            date = LocalDate.parse(date),
        ),
    )
}

private data class Projection(
    val amountMinor: Long?,
    val currency: String?,
    val source: DefaultCurrencyConversionSource,
    val transferId: Long?,
)
