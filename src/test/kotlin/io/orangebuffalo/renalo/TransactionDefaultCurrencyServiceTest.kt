package io.orangebuffalo.renalo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.DefaultCurrencyConversionSource
import io.orangebuffalo.renalo.tracking.FundsTransfer
import io.orangebuffalo.renalo.tracking.FundsTransferRepository
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
import java.time.LocalDate

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

    @Test
    fun usesTheExactLaterConversionForForeignIncome() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val foreignAccount = saveAccount(user, "USD", "USD")
        val income = saveTransaction(user, foreignAccount, TransactionType.INCOME, "2026-01-10", 12_345)
        val sameCurrencyExpense = saveTransaction(user, defaultAccount, TransactionType.EXPENSE, "2026-01-11", 987)
        val conversion = saveTransfer(user, foreignAccount, defaultAccount, "2026-01-12", 12_345, 19_876)

        transactionDefaultCurrencyService.recalculateForUser(user.id!!)

        transactionRepository.findById(income.id!!).get().apply {
            defaultCurrencyAmountMinor.shouldBe(19_876)
            defaultCurrency.shouldBe("AUD")
            defaultCurrencyConversionSource.shouldBe(DefaultCurrencyConversionSource.ACTUAL_TRANSFER)
            defaultCurrencyConversionTransferId.shouldBe(conversion.id)
        }
        transactionRepository.findById(sameCurrencyExpense.id!!).get().apply {
            defaultCurrencyAmountMinor.shouldBe(987)
            defaultCurrency.shouldBe("AUD")
            defaultCurrencyConversionSource.shouldBe(DefaultCurrencyConversionSource.SAME_CURRENCY)
            defaultCurrencyConversionTransferId.shouldBe(null)
        }
    }

    @Test
    fun prefersExpenseFundingBeforeTheExpenseOverOppositeFlowEvidence() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val foreignAccount = saveAccount(user, "USD", "USD")
        val expense = saveTransaction(user, foreignAccount, TransactionType.EXPENSE, "2026-02-10", 3_000)
        saveTransfer(user, foreignAccount, defaultAccount, "2026-02-11", 3_000, 9_000)
        val funding = saveTransfer(user, defaultAccount, foreignAccount, "2026-02-09", 6_000, 3_000)

        transactionDefaultCurrencyService.recalculateForUser(user.id!!)

        transactionRepository.findById(expense.id!!).get().apply {
            defaultCurrencyAmountMinor.shouldBe(6_000)
            defaultCurrencyConversionSource.shouldBe(DefaultCurrencyConversionSource.ACTUAL_TRANSFER)
            defaultCurrencyConversionTransferId.shouldBe(funding.id)
        }
    }

    @Test
    fun deterministicallyRanksAccountAmountDateAndTransferId() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val transactionAccount = saveAccount(user, "USD Wallet", "USD")
        val otherUsdAccount = saveAccount(user, "Other USD", "USD")
        val income = saveTransaction(user, transactionAccount, TransactionType.INCOME, "2026-02-10", 100)
        saveTransfer(user, otherUsdAccount, defaultAccount, "2026-02-10", 100, 1_000)
        saveTransfer(user, transactionAccount, defaultAccount, "2026-02-11", 200, 600)
        saveTransfer(user, transactionAccount, defaultAccount, "2026-02-20", 100, 400)
        val selected = saveTransfer(user, transactionAccount, defaultAccount, "2026-02-12", 100, 500)
        saveTransfer(user, transactionAccount, defaultAccount, "2026-02-12", 100, 600)

        transactionDefaultCurrencyService.recalculateForUser(user.id!!)

        transactionRepository.findById(income.id!!).get().apply {
            defaultCurrencyAmountMinor.shouldBe(500)
            defaultCurrencyConversionTransferId.shouldBe(selected.id)
        }
    }

    @Test
    fun recordsUnavailableEvidenceAndRoundsToDefaultCurrencyMinorUnits() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val usdAccount = saveAccount(user, "USD", "USD")
        val eurAccount = saveAccount(user, "EUR", "EUR")
        val gbpAccount = saveAccount(user, "GBP", "GBP")
        val halfMinorUnit = saveTransaction(user, usdAccount, TransactionType.INCOME, "2026-03-01", 1)
        val belowHalfMinorUnit = saveTransaction(user, eurAccount, TransactionType.INCOME, "2026-03-01", 1)
        val unavailable = saveTransaction(user, gbpAccount, TransactionType.INCOME, "2026-03-01", 100)
        saveTransfer(user, usdAccount, defaultAccount, "2026-03-02", 2, 1)
        saveTransfer(user, eurAccount, defaultAccount, "2026-03-02", 100, 1)

        transactionDefaultCurrencyService.recalculateForUser(user.id!!)

        transactionRepository.findById(halfMinorUnit.id!!).get().defaultCurrencyAmountMinor.shouldBe(1)
        transactionRepository.findById(belowHalfMinorUnit.id!!).get().apply {
            defaultCurrencyAmountMinor.shouldBe(0)
            defaultCurrencyConversionSource.shouldBe(DefaultCurrencyConversionSource.ACTUAL_TRANSFER)
        }
        transactionRepository.findById(unavailable.id!!).get().apply {
            defaultCurrencyAmountMinor.shouldBe(null)
            defaultCurrency.shouldBe("AUD")
            defaultCurrencyConversionSource.shouldBe(DefaultCurrencyConversionSource.UNAVAILABLE)
            defaultCurrencyConversionTransferId.shouldBe(null)
        }
    }

    @Test
    fun restatesTheWholeHistoryWhenTheDefaultCurrencyChanges() {
        val user = saveUser()
        val audAccount = saveAccount(user, "AUD", "AUD", isDefault = true)
        val usdAccount = saveAccount(user, "USD", "USD")
        val audIncome = saveTransaction(user, audAccount, TransactionType.INCOME, "2026-04-01", 500)
        val usdIncome = saveTransaction(user, usdAccount, TransactionType.INCOME, "2026-04-01", 1_000)
        saveTransfer(user, usdAccount, audAccount, "2026-04-02", 1_000, 1_500)

        transactionDefaultCurrencyService.recalculateForUser(user.id!!)
        transactionRepository.findById(audIncome.id!!).get().defaultCurrencyAmountMinor.shouldBe(500)
        transactionRepository.findById(usdIncome.id!!).get().defaultCurrencyAmountMinor.shouldBe(1_500)

        trackingAccountRepository.clearDefaultForUser(user.id!!)
        trackingAccountRepository.update(usdAccount.copy(isDefault = true))
        transactionDefaultCurrencyService.recalculateForUser(user.id!!)

        transactionRepository.findById(audIncome.id!!).get().apply {
            defaultCurrencyAmountMinor.shouldBe(333)
            defaultCurrency.shouldBe("USD")
            defaultCurrencyConversionSource.shouldBe(DefaultCurrencyConversionSource.ACTUAL_TRANSFER)
        }
        transactionRepository.findById(usdIncome.id!!).get().apply {
            defaultCurrencyAmountMinor.shouldBe(1_000)
            defaultCurrency.shouldBe("USD")
            defaultCurrencyConversionSource.shouldBe(DefaultCurrencyConversionSource.SAME_CURRENCY)
            defaultCurrencyConversionTransferId.shouldBe(null)
        }
    }

    @Test
    fun rejectsConvertedAmountsThatOverflowLong() {
        val user = saveUser()
        val defaultAccount = saveAccount(user, "Main", "AUD", isDefault = true)
        val foreignAccount = saveAccount(user, "USD", "USD")
        saveTransaction(user, foreignAccount, TransactionType.INCOME, "2026-05-01", Long.MAX_VALUE)
        saveTransfer(user, foreignAccount, defaultAccount, "2026-05-02", 1, Long.MAX_VALUE)

        shouldThrow<ArithmeticException> {
            transactionDefaultCurrencyService.recalculateForUser(user.id!!)
        }
    }

    private fun saveUser(): User = userRepository.save(
        User(
            username = "alice",
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
    ): Transaction = transactionRepository.save(
        Transaction(
            userId = user.id!!,
            type = type,
            trackingAccountId = account.id!!,
            categoryId = 1,
            date = LocalDate.parse(date),
            amountMinor = amountMinor,
        ),
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
