package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.Expense
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.ExpenseRepository
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class ExpenseApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var expenseRepository: ExpenseRepository


    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun requiresRegularUserForExpenses() {
        val alice = saveUser("alice", UserType.USER)
        val account = saveAccount(alice, "Main", "AUD")
        val category = saveCategory(alice, "Groceries")
        saveUser("admin", UserType.ADMIN)
        val userToken = api().login("alice", "password")
        val adminToken = api().login("admin", "password")
        val body = expenseJson(account, category, "2026-06-15T10:30:00Z", 1234, "Milk")

        api().get("/api/tracking/expenses", null).statusCode().shouldBe(401)
        api().get("/api/tracking/expenses", adminToken).statusCode().shouldBe(403)
        api().postJson("/api/tracking/expenses", body, null).statusCode().shouldBe(401)
        api().postJson("/api/tracking/expenses", body, adminToken).statusCode().shouldBe(403)
        api().get("/api/tracking/expenses", userToken).statusCode().shouldBe(200)
    }

    @Test
    fun listsOnlyCurrentUsersExpensesWithAccountCurrencyAndCategory() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val account = saveAccount(alice, "Everyday", "AUD")
        val category = saveCategory(alice, "Groceries")
        val older = saveExpense(alice, account, category, "2026-06-14T10:30:00Z", 1200, "Bread")
        val newer = saveExpense(alice, account, category, "2026-06-15T10:30:00Z", 3400, null)
        saveExpense(bob, saveAccount(bob, "Bob account", "USD"), saveCategory(bob, "Bob category"), "2026-06-16T10:30:00Z", 999, "Hidden")

        val response = api().get("/api/tracking/expenses", api().login("alice", "password"))

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${newer.id},
                    "trackingAccount": {
                      "id": ${account.id},
                      "name": "Everyday",
                      "currency": "AUD"
                    },
                    "category": {
                      "id": ${category.id},
                      "name": "Groceries"
                    },
                    "dateTime": "2026-06-15T10:30:00Z",
                    "amountMinor": 3400
                  },
                  {
                    "id": ${older.id},
                    "trackingAccount": {
                      "id": ${account.id},
                      "name": "Everyday",
                      "currency": "AUD"
                    },
                    "category": {
                      "id": ${category.id},
                      "name": "Groceries"
                    },
                    "dateTime": "2026-06-14T10:30:00Z",
                    "amountMinor": 1200,
                    "notes": "Bread"
                  }
                ]
            """.trimIndent(),
        )
    }

    @Test
    fun createsUpdatesAndDeletesExpenseForCurrentUser() {
        val alice = saveUser("alice", UserType.USER)
        val account = saveAccount(alice, "Main", "AUD")
        val savings = saveAccount(alice, "Savings", "EUR")
        val groceries = saveCategory(alice, "Groceries")
        val rent = saveCategory(alice, "Rent")
        val token = api().login("alice", "password")

        val createResponse = api().postJson(
            "/api/tracking/expenses",
            expenseJson(account, groceries, "2026-06-15T10:30:00Z", 1234, " Milk "),
            token,
        )

        createResponse.statusCode().shouldBe(201)
        val expense = expenseRepository.findByUserIdOrderByDateTimeDesc(alice.id!!).single()
        createResponse.body().shouldEqualJson(
            """
                {
                  "id": ${expense.id},
                  "trackingAccount": {
                    "id": ${account.id},
                    "name": "Main",
                    "currency": "AUD"
                  },
                  "category": {
                    "id": ${groceries.id},
                    "name": "Groceries"
                  },
                  "dateTime": "2026-06-15T10:30:00Z",
                  "amountMinor": 1234,
                  "notes": "Milk"
                }
            """.trimIndent(),
        )

        val updateResponse = api().patchJson(
            "/api/tracking/expenses/${expense.id}",
            expenseJson(savings, rent, "2026-06-16T09:00:00Z", 2000, null),
            token,
        )

        updateResponse.statusCode().shouldBe(200)
        updateResponse.body().shouldEqualJson(
            """
                {
                  "id": ${expense.id},
                  "trackingAccount": {
                    "id": ${savings.id},
                    "name": "Savings",
                    "currency": "EUR"
                  },
                  "category": {
                    "id": ${rent.id},
                    "name": "Rent"
                  },
                  "dateTime": "2026-06-16T09:00:00Z",
                  "amountMinor": 2000
                }
            """.trimIndent(),
        )
        expenseRepository.findById(expense.id!!).get().trackingAccountId.shouldBe(savings.id)

        api().delete("/api/tracking/expenses/${expense.id}", token).statusCode().shouldBe(204)
        expenseRepository.findById(expense.id!!).isPresent.shouldBe(false)
    }

    @Test
    fun rejectsInvalidAndOtherUsersExpenseReferences() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val aliceAccount = saveAccount(alice, "Main", "AUD")
        val aliceCategory = saveCategory(alice, "Groceries")
        val bobAccount = saveAccount(bob, "Bob account", "USD")
        val bobCategory = saveCategory(bob, "Bob category")
        val bobExpense = saveExpense(bob, bobAccount, bobCategory, "2026-06-15T10:30:00Z", 999, null)
        val token = api().login("alice", "password")

        api().postJson(
            "/api/tracking/expenses",
            expenseJson(aliceAccount, aliceCategory, "2026-06-15T10:30:00Z", 0, null),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/expenses",
            expenseJson(bobAccount, aliceCategory, "2026-06-15T10:30:00Z", 1000, null),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/expenses",
            expenseJson(aliceAccount, bobCategory, "2026-06-15T10:30:00Z", 1000, null),
            token,
        ).statusCode().shouldBe(400)
        api().get("/api/tracking/expenses/${bobExpense.id}", token).statusCode().shouldBe(404)
        api().patchJson(
            "/api/tracking/expenses/${bobExpense.id}",
            expenseJson(aliceAccount, aliceCategory, "2026-06-15T10:30:00Z", 1000, null),
            token,
        ).statusCode().shouldBe(404)
        api().delete("/api/tracking/expenses/${bobExpense.id}", token).statusCode().shouldBe(404)
    }

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = type,
        ),
    )

    private fun saveAccount(user: User, name: String, currency: String): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = currency,
            initialBalanceMinor = 0,
            isDefault = name == "Main",
        ),
    )

    private fun saveCategory(user: User, name: String): ExpenseCategory = expenseCategoryRepository.save(
        ExpenseCategory(
            userId = user.id!!,
            name = name,
        ),
    )

    private fun saveExpense(
        user: User,
        account: TrackingAccount,
        category: ExpenseCategory,
        dateTime: String,
        amountMinor: Long,
        notes: String?,
    ): Expense = expenseRepository.save(
        Expense(
            userId = user.id!!,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            dateTime = OffsetDateTime.parse(dateTime),
            amountMinor = amountMinor,
            notes = notes,
        ),
    )

    private fun expenseJson(
        account: TrackingAccount,
        category: ExpenseCategory,
        dateTime: String,
        amountMinor: Long,
        notes: String?,
    ) = """
        {
          "trackingAccountId": ${account.id},
          "categoryId": ${category.id},
          "dateTime": "$dateTime",
          "amountMinor": $amountMinor,
          "notes": ${notes?.let { "\"$it\"" } ?: "null"}
        }
    """.trimIndent()
}
