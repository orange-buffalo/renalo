package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.FundsTransferRepository
import io.orangebuffalo.renalo.tracking.IncomeCategoryRepository
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.tracking.TransactionRepository
import io.orangebuffalo.renalo.tracking.TransactionType
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class ToshlImportApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var incomeCategoryRepository: IncomeCategoryRepository

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var fundsTransferRepository: FundsTransferRepository

    @Test
    fun requiresRegularUserForToshlImport() {
        saveUser("alice", UserType.USER)
        saveUser("admin", UserType.ADMIN)
        val userToken = api().login("alice", "password")
        val adminToken = api().login("admin", "password")

        api().postJson("/api/import/toshl", toshlRequest(sampleCsv), null).statusCode().shouldBe(401)
        api().postJson("/api/import/toshl", toshlRequest(sampleCsv), adminToken).statusCode().shouldBe(403)
        api().postJson("/api/import/toshl", toshlRequest(sampleCsv), userToken).statusCode().shouldBe(200)
    }

    @Test
    fun importsToshlExpensesIncomeTransfersAccountsCategoriesAndMetadata() {
        val alice = saveUser("alice", UserType.USER)
        saveAccount(alice, "Acc1", "AUD", isDefault = true)
        val token = api().login("alice", "password")

        val response = api().postJson("/api/import/toshl", toshlRequest(sampleCsv), token)

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "importedExpenses": 2,
                  "importedIncomes": 1,
                  "skippedDuplicateExpenses": 0,
                  "skippedDuplicateIncomes": 0,
                  "importedTransfers": 1,
                  "skippedDuplicateTransfers": 0,
                  "warnings": [],
                  "report": [
                    {
                      "lineNumber": 2,
                      "date": "2026-06-01",
                      "account": "Acc1",
                      "category": "Cat 1",
                      "type": "EXPENSE",
                      "amountMinor": 10400,
                      "currency": "AUD",
                      "status": "IMPORTED",
                      "reason": "Imported as expense."
                    },
                    {
                      "lineNumber": 3,
                      "date": "2026-06-03",
                      "account": "Acc1",
                      "category": "Cat 2",
                      "type": "EXPENSE",
                      "amountMinor": 50000,
                      "currency": "AUD",
                      "status": "IMPORTED",
                      "reason": "Imported as expense."
                    },
                    {
                      "lineNumber": 4,
                      "date": "2026-06-06",
                      "account": "Acc2",
                      "category": "Transfer",
                      "type": "EXPENSE",
                      "amountMinor": 2198700,
                      "currency": "EUR",
                      "status": "IMPORTED",
                      "reason": "Imported as transfer source."
                    },
                    {
                      "lineNumber": 5,
                      "date": "2026-06-06",
                      "account": "Acc1",
                      "category": "Transfer",
                      "type": "INCOME",
                      "amountMinor": 2198700,
                      "currency": "EUR",
                      "status": "IMPORTED",
                      "reason": "Imported as transfer target."
                    },
                    {
                      "lineNumber": 6,
                      "date": "2026-06-22",
                      "account": "Acc2",
                      "category": "Cat x",
                      "type": "INCOME",
                      "amountMinor": 5498700,
                      "currency": "EUR",
                      "status": "IMPORTED",
                      "reason": "Imported as income."
                    }
                  ]
                }
            """.trimIndent(),
        )

        trackingAccountRepository.findByUserIdOrderByName(alice.id!!).map { it.name to it.currency }
            .shouldContainExactly("Acc1" to "AUD", "Acc2" to "EUR")
        expenseCategoryRepository.findByUserIdOrderByName(alice.id!!).map { it.name }
            .shouldContainExactly("Cat 1", "Cat 2")
        incomeCategoryRepository.findByUserIdOrderByName(alice.id!!).map { it.name }
            .shouldContainExactly("Cat x")

        val expenses = transactionRepository.findByUserIdAndTypeOrderByDateDesc(alice.id!!, TransactionType.EXPENSE)
        expenses.map { it.amountMinor to it.notes }.shouldContainExactly(
            50_000L to "some description\nTags: work, reimbursable",
            10_400L to null,
        )
        expenses.forEach { it.metadata.shouldBe(mapOf("source" to "toshl")) }

        val income = transactionRepository.findByUserIdAndTypeOrderByDateDesc(alice.id!!, TransactionType.INCOME).single()
        income.amountMinor.shouldBe(5_498_700L)
        income.metadata.shouldBe(mapOf("source" to "toshl"))

        val transfer = fundsTransferRepository.findByUserIdOrderByDateDesc(alice.id!!).single()
        val accounts = trackingAccountRepository.findByUserIdOrderByName(alice.id!!).associateBy { it.name }
        transfer.sourceAccountId.shouldBe(accounts.getValue("Acc2").id)
        transfer.targetAccountId.shouldBe(accounts.getValue("Acc1").id)
        transfer.sourceAmountMinor.shouldBe(2_198_700L)
        transfer.targetAmountMinor.shouldBe(6_798_734L)
    }

    @Test
    fun skipsDuplicateToshlRowsWhenImportedAgain() {
        val alice = saveUser("alice", UserType.USER)
        saveAccount(alice, "Acc1", "AUD", isDefault = true)
        val token = api().login("alice", "password")
        api().postJson("/api/import/toshl", toshlRequest(sampleCsv), token).statusCode().shouldBe(200)

        val response = api().postJson("/api/import/toshl", toshlRequest(sampleCsv), token)

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "importedExpenses": 0,
                  "importedIncomes": 0,
                  "skippedDuplicateExpenses": 2,
                  "skippedDuplicateIncomes": 1,
                  "importedTransfers": 0,
                  "skippedDuplicateTransfers": 1,
                  "warnings": [],
                  "report": [
                    {
                      "lineNumber": 2,
                      "date": "2026-06-01",
                      "account": "Acc1",
                      "category": "Cat 1",
                      "type": "EXPENSE",
                      "amountMinor": 10400,
                      "currency": "AUD",
                      "status": "SKIPPED_DUPLICATE",
                      "reason": "Duplicate expense by date, type, and amount."
                    },
                    {
                      "lineNumber": 3,
                      "date": "2026-06-03",
                      "account": "Acc1",
                      "category": "Cat 2",
                      "type": "EXPENSE",
                      "amountMinor": 50000,
                      "currency": "AUD",
                      "status": "SKIPPED_DUPLICATE",
                      "reason": "Duplicate expense by date, type, and amount."
                    },
                    {
                      "lineNumber": 4,
                      "date": "2026-06-06",
                      "account": "Acc2",
                      "category": "Transfer",
                      "type": "EXPENSE",
                      "amountMinor": 2198700,
                      "currency": "EUR",
                      "status": "SKIPPED_DUPLICATE",
                      "reason": "Duplicate transfer pair."
                    },
                    {
                      "lineNumber": 5,
                      "date": "2026-06-06",
                      "account": "Acc1",
                      "category": "Transfer",
                      "type": "INCOME",
                      "amountMinor": 2198700,
                      "currency": "EUR",
                      "status": "SKIPPED_DUPLICATE",
                      "reason": "Duplicate transfer pair."
                    },
                    {
                      "lineNumber": 6,
                      "date": "2026-06-22",
                      "account": "Acc2",
                      "category": "Cat x",
                      "type": "INCOME",
                      "amountMinor": 5498700,
                      "currency": "EUR",
                      "status": "SKIPPED_DUPLICATE",
                      "reason": "Duplicate income by date, type, and amount."
                    }
                  ]
                }
            """.trimIndent(),
        )
        transactionRepository.findByUserIdAndTypeOrderByDateDesc(alice.id!!, TransactionType.EXPENSE).size.shouldBe(2)
        transactionRepository.findByUserIdAndTypeOrderByDateDesc(alice.id!!, TransactionType.INCOME).size.shouldBe(1)
        fundsTransferRepository.findByUserIdOrderByDateDesc(alice.id!!).size.shouldBe(1)
    }

    @Test
    fun reportsUnreconciledTransferRowsWithoutImportingThem() {
        val alice = saveUser("alice", UserType.USER)
        saveAccount(alice, "Main", "AUD", isDefault = true)

        val response = api().postJson(
            "/api/import/toshl",
            toshlRequest(
                """
                    Date,Account,Category,Tags,Expense amount,Income amount,Currency,In main currency,Main currency,Description
                    6/6/26,Acc2,Transfer,,100.00,0,EUR,170.00,AUD,
                """.trimIndent(),
            ),
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "importedExpenses": 0,
                  "importedIncomes": 0,
                  "skippedDuplicateExpenses": 0,
                  "skippedDuplicateIncomes": 0,
                  "importedTransfers": 0,
                  "skippedDuplicateTransfers": 0,
                  "warnings": [
                    {
                      "lineNumber": 2,
                      "date": "2026-06-06",
                      "account": "Acc2",
                      "amountMinor": 10000,
                      "currency": "EUR",
                      "type": "EXPENSE",
                      "description": "Transfer row could not be matched with its opposite side."
                    }
                  ],
                  "report": [
                    {
                      "lineNumber": 2,
                      "date": "2026-06-06",
                      "account": "Acc2",
                      "category": "Transfer",
                      "type": "EXPENSE",
                      "amountMinor": 10000,
                      "currency": "EUR",
                      "status": "UNMATCHED_TRANSFER",
                      "reason": "Transfer row could not be matched with its opposite side."
                    }
                  ]
                }
            """.trimIndent(),
        )
        trackingAccountRepository.findByUserIdOrderByName(alice.id!!).map { it.name }.shouldContainExactly("Main")
        fundsTransferRepository.findByUserIdOrderByDateDesc(alice.id!!).shouldBe(emptyList())
        transactionRepository.findByUserIdAndTypeOrderByDateDesc(alice.id!!, TransactionType.EXPENSE).shouldBe(emptyList())
    }

    @Test
    fun matchesTransferAgainstDifferentAccountWhenSameAccountCandidateAppearsFirst() {
        val alice = saveUser("alice", UserType.USER)
        saveAccount(alice, "Acc1", "AUD", isDefault = true)
        saveAccount(alice, "Acc2", "AUD", isDefault = false)

        val response = api().postJson(
            "/api/import/toshl",
            toshlRequest(
                """
                    Date,Account,Category,Tags,Expense amount,Income amount,Currency,In main currency,Main currency,Description
                    6/6/26,Acc1,Transfer,,100.00,0,AUD,100.00,AUD,
                    6/6/26,Acc1,Transfer,,0,100.00,AUD,100.00,AUD,
                    6/6/26,Acc2,Transfer,,0,100.00,AUD,100.00,AUD,
                """.trimIndent(),
            ),
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "importedExpenses": 0,
                  "importedIncomes": 0,
                  "skippedDuplicateExpenses": 0,
                  "skippedDuplicateIncomes": 0,
                  "importedTransfers": 1,
                  "skippedDuplicateTransfers": 0,
                  "warnings": [
                    {
                      "lineNumber": 3,
                      "date": "2026-06-06",
                      "account": "Acc1",
                      "amountMinor": 10000,
                      "currency": "AUD",
                      "type": "INCOME",
                      "description": "Transfer row could not be matched with its opposite side."
                    }
                  ],
                  "report": [
                    {
                      "lineNumber": 2,
                      "date": "2026-06-06",
                      "account": "Acc1",
                      "category": "Transfer",
                      "type": "EXPENSE",
                      "amountMinor": 10000,
                      "currency": "AUD",
                      "status": "IMPORTED",
                      "reason": "Imported as transfer source."
                    },
                    {
                      "lineNumber": 3,
                      "date": "2026-06-06",
                      "account": "Acc1",
                      "category": "Transfer",
                      "type": "INCOME",
                      "amountMinor": 10000,
                      "currency": "AUD",
                      "status": "UNMATCHED_TRANSFER",
                      "reason": "Transfer row could not be matched with its opposite side."
                    },
                    {
                      "lineNumber": 4,
                      "date": "2026-06-06",
                      "account": "Acc2",
                      "category": "Transfer",
                      "type": "INCOME",
                      "amountMinor": 10000,
                      "currency": "AUD",
                      "status": "IMPORTED",
                      "reason": "Imported as transfer target."
                    }
                  ]
                }
            """.trimIndent(),
        )
        fundsTransferRepository.findByUserIdOrderByDateDesc(alice.id!!).single().targetAccountId
            .shouldBe(trackingAccountRepository.findByUserIdOrderByName(alice.id!!).single { it.name == "Acc2" }.id)
    }

    @Test
    fun usesNonTransferRowsToDetectAccountCurrenciesAndStoresConvertedTransferAmounts() {
        val alice = saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        val response = api().postJson(
            "/api/import/toshl",
            toshlRequest(
                """
                    Date,Account,Category,Tags,Expense amount,Income amount,Currency,In main currency,Main currency,Description
                    1/6/26,Euro account,Setup income,,0,1.00,EUR,1.70,AUD,
                    1/6/26,Aud account,Setup expense,,1.00,0,AUD,1.00,AUD,
                    6/6/26,Euro account,Transfer,,100.00,0,EUR,170.00,AUD,
                    6/6/26,Aud account,Transfer,,0,100.00,EUR,150.00,AUD,
                """.trimIndent(),
            ),
            token,
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "importedExpenses": 1,
                  "importedIncomes": 1,
                  "skippedDuplicateExpenses": 0,
                  "skippedDuplicateIncomes": 0,
                  "importedTransfers": 1,
                  "skippedDuplicateTransfers": 0,
                  "warnings": [],
                  "report": [
                    {
                      "lineNumber": 2,
                      "date": "2026-06-01",
                      "account": "Euro account",
                      "category": "Setup income",
                      "type": "INCOME",
                      "amountMinor": 100,
                      "currency": "EUR",
                      "status": "IMPORTED",
                      "reason": "Imported as income."
                    },
                    {
                      "lineNumber": 3,
                      "date": "2026-06-01",
                      "account": "Aud account",
                      "category": "Setup expense",
                      "type": "EXPENSE",
                      "amountMinor": 100,
                      "currency": "AUD",
                      "status": "IMPORTED",
                      "reason": "Imported as expense."
                    },
                    {
                      "lineNumber": 4,
                      "date": "2026-06-06",
                      "account": "Euro account",
                      "category": "Transfer",
                      "type": "EXPENSE",
                      "amountMinor": 10000,
                      "currency": "EUR",
                      "status": "IMPORTED",
                      "reason": "Imported as transfer source."
                    },
                    {
                      "lineNumber": 5,
                      "date": "2026-06-06",
                      "account": "Aud account",
                      "category": "Transfer",
                      "type": "INCOME",
                      "amountMinor": 10000,
                      "currency": "EUR",
                      "status": "IMPORTED",
                      "reason": "Imported as transfer target."
                    }
                  ]
                }
            """.trimIndent(),
        )
        val accounts = trackingAccountRepository.findByUserIdOrderByName(alice.id!!).associateBy { it.name }
        accounts.getValue("Euro account").currency.shouldBe("EUR")
        accounts.getValue("Aud account").currency.shouldBe("AUD")
        val transfer = fundsTransferRepository.findByUserIdOrderByDateDesc(alice.id!!).single()
        transfer.sourceAccountId.shouldBe(accounts.getValue("Euro account").id)
        transfer.targetAccountId.shouldBe(accounts.getValue("Aud account").id)
        transfer.sourceAmountMinor.shouldBe(10_000L)
        transfer.targetAmountMinor.shouldBe(15_000L)
    }

    @Test
    fun reportsTransfersUnmatchedWhenAccountCurrencyCannotBeDetectedFromTransactions() {
        val alice = saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        val response = api().postJson(
            "/api/import/toshl",
            toshlRequest(
                """
                    Date,Account,Category,Tags,Expense amount,Income amount,Currency,In main currency,Main currency,Description
                    6/6/26,Euro account,Transfer,,100.00,0,EUR,170.00,AUD,
                    6/6/26,Aud account,Transfer,,0,100.00,EUR,150.00,AUD,
                """.trimIndent(),
            ),
            token,
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "importedExpenses": 0,
                  "importedIncomes": 0,
                  "skippedDuplicateExpenses": 0,
                  "skippedDuplicateIncomes": 0,
                  "importedTransfers": 0,
                  "skippedDuplicateTransfers": 0,
                  "warnings": [
                    {
                      "lineNumber": 2,
                      "date": "2026-06-06",
                      "account": "Euro account",
                      "amountMinor": 10000,
                      "currency": "EUR",
                      "type": "EXPENSE",
                      "description": "Transfer row could not be matched with its opposite side."
                    },
                    {
                      "lineNumber": 3,
                      "date": "2026-06-06",
                      "account": "Aud account",
                      "amountMinor": 10000,
                      "currency": "EUR",
                      "type": "INCOME",
                      "description": "Transfer row could not be matched with its opposite side."
                    }
                  ],
                  "report": [
                    {
                      "lineNumber": 2,
                      "date": "2026-06-06",
                      "account": "Euro account",
                      "category": "Transfer",
                      "type": "EXPENSE",
                      "amountMinor": 10000,
                      "currency": "EUR",
                      "status": "UNMATCHED_TRANSFER",
                      "reason": "Transfer row could not be matched with account currencies."
                    },
                    {
                      "lineNumber": 3,
                      "date": "2026-06-06",
                      "account": "Aud account",
                      "category": "Transfer",
                      "type": "INCOME",
                      "amountMinor": 10000,
                      "currency": "EUR",
                      "status": "UNMATCHED_TRANSFER",
                      "reason": "Transfer row could not be matched with account currencies."
                    }
                  ]
                }
            """.trimIndent(),
        )
        trackingAccountRepository.findByUserIdOrderByName(alice.id!!).shouldBe(emptyList())
        fundsTransferRepository.findByUserIdOrderByDateDesc(alice.id!!).shouldBe(emptyList())
    }

    @Test
    fun rejectsInvalidToshlFiles() {
        val alice = saveUser("alice", UserType.USER)
        val token = api().login("alice", "password")

        api().postJson("/api/import/toshl", toshlRequest(""), token).statusCode().shouldBe(400)
        api().postJson(
            "/api/import/toshl",
            toshlRequest(
                """
                    Date,Account,Category,Tags,Expense amount,Income amount,Currency,In main currency,Main currency,Description
                    1/6/26,Acc1,Cat 1,,0,0,AUD,0,AUD,
                """.trimIndent(),
            ),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/import/toshl",
            toshlRequest(
                """
                    Date,Account,Category,Tags,Expense amount,Income amount,Currency,In main currency,Main currency,Description
                    1/6/26,Acc1,Cat 1,,1.00,0,NOPE,1.00,AUD,
                """.trimIndent(),
            ),
            token,
        ).statusCode().shouldBe(400)
    }

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = type,
        ),
    )

    private fun saveAccount(user: User, name: String, currency: String, isDefault: Boolean): TrackingAccount =
        trackingAccountRepository.save(
            TrackingAccount(
                userId = user.id!!,
                name = name,
                currency = currency,
                initialBalanceMinor = 0,
                isDefault = isDefault,
            ),
        )

    private fun toshlRequest(csv: String): String =
        """
            {"csvContent":${jsonString(csv)}}
        """.trimIndent()

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private companion object {
        val sampleCsv = """
            Date,Account,Category,Tags,Expense amount,Income amount,Currency,In main currency,Main currency,Description
            1/6/26,Acc1,Cat 1,,104.00,0,AUD,104.00,AUD,
            3/6/26,Acc1,Cat 2,"work, reimbursable",500.00,0,AUD,500.00,AUD,some description
            6/6/26,Acc2,Transfer,,"21,987.00",0,EUR,"67,987.34",AUD,
            6/6/26,Acc1,Transfer,,0,"21,987.00",EUR,"67,987.34",AUD,
            22/6/26,Acc2,Cat x,,0,"54,987.00",EUR,"34,922.45",AUD,
        """.trimIndent()
    }
}
