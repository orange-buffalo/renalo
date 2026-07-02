package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.IncomeCategory
import io.orangebuffalo.renalo.tracking.IncomeCategoryRepository
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.tracking.Transaction
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
class ExpenseCategoryApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var incomeCategoryRepository: IncomeCategoryRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun requiresRegularUserForExpenseCategories() {
        saveUser("alice", UserType.USER)
        saveUser("admin", UserType.ADMIN)
        val userToken = api().login("alice", "password")
        val adminToken = api().login("admin", "password")
        val body = """
            {"name":"Groceries"}
        """.trimIndent()

        api().get("/api/tracking/expense-categories", null).statusCode().shouldBe(401)
        api().get("/api/tracking/expense-categories", adminToken).statusCode().shouldBe(403)
        api().postJson("/api/tracking/expense-categories", body, null).statusCode().shouldBe(401)
        api().postJson("/api/tracking/expense-categories", body, adminToken).statusCode().shouldBe(403)
        api().get("/api/tracking/expense-categories", userToken).statusCode().shouldBe(200)
    }

    @Test
    fun listsOnlyCurrentUsersExpenseCategories() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val groceries = saveCategory(alice, "Groceries")
        val rent = saveCategory(alice, "Rent")
        saveCategory(bob, "Bob category")

        val response = api().get("/api/tracking/expense-categories", api().login("alice", "password"))

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${groceries.id},
                    "name": "Groceries"
                  },
                  {
                    "id": ${rent.id},
                    "name": "Rent"
                  }
                ]
            """.trimIndent(),
        )
    }

    @Test
    fun createsAndUpdatesExpenseCategoryForCurrentUser() {
        val alice = saveUser("alice", UserType.USER)
        saveCategory(alice, "General")
        val token = api().login("alice", "password")

        val createResponse = api().postJson(
            "/api/tracking/expense-categories",
            """
                {"name":" Groceries "}
            """.trimIndent(),
            token,
        )

        createResponse.statusCode().shouldBe(201)
        val groceries = expenseCategoryRepository.findByUserIdOrderByName(alice.id!!).first { it.name == "Groceries" }
        createResponse.body().shouldEqualJson(
            """
                {
                  "id": ${groceries.id},
                  "name": "Groceries"
                }
            """.trimIndent(),
        )

        val updateResponse = api().patchJson(
            "/api/tracking/expense-categories/${groceries.id}",
            """
                {"name":"Food"}
            """.trimIndent(),
            token,
        )

        updateResponse.statusCode().shouldBe(200)
        updateResponse.body().shouldEqualJson(
            """
                {
                  "id": ${groceries.id},
                  "name": "Food"
                }
            """.trimIndent(),
        )
        expenseCategoryRepository.findById(groceries.id!!).get().name.shouldBe("Food")
    }

    @Test
    fun createsDefaultExpenseCategoryForNewRegularUser() {
        saveUser("admin", UserType.ADMIN)
        val adminToken = api().login("admin", "password")

        val response = api().postJson(
            "/api/users",
            """
                {"username":"alice","type":"USER"}
            """.trimIndent(),
            adminToken,
        )

        response.statusCode().shouldBe(201)
        val alice = userRepository.findByUsername("alice")!!
        val categories = expenseCategoryRepository.findByUserIdOrderByName(alice.id!!)
        categories.size.shouldBe(1)
        categories.single().name.shouldBe("General")
    }

    @Test
    fun rejectsInvalidAndOtherUsersExpenseCategories() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val bobCategory = saveCategory(bob, "Bob category")
        val token = api().login("alice", "password")

        api().postJson(
            "/api/tracking/expense-categories",
            """
                {"name":""}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(400)
        api().get("/api/tracking/expense-categories/${bobCategory.id}", token).statusCode().shouldBe(404)
        api().patchJson(
            "/api/tracking/expense-categories/${bobCategory.id}",
            """
                {"name":"Hacked"}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(404)
        expenseCategoryRepository.findByUserIdOrderByName(alice.id!!).size.shouldBe(0)
    }

    @Test
    fun providesExpenseCategoryMergeSummary() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val groceries = saveCategory(alice, "Groceries")
        val rent = saveCategory(alice, "Rent")
        val travel = saveCategory(alice, "Travel")
        val bobCategory = saveCategory(bob, "Bob category")
        val account = saveAccount(alice)
        val incomeCategory = saveIncomeCategory(alice)
        saveTransaction(alice, account, groceries, TransactionType.EXPENSE, 1_000)
        saveTransaction(alice, account, groceries, TransactionType.EXPENSE, 2_000)
        saveTransaction(alice, account, incomeCategory, TransactionType.INCOME, 3_000)
        saveTransaction(bob, saveAccount(bob), bobCategory, TransactionType.EXPENSE, 4_000)

        val response = api().get(
            "/api/tracking/expense-categories/${groceries.id}/merge-summary",
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "sourceCategory": {
                    "id": ${groceries.id},
                    "name": "Groceries"
                  },
                  "expensesCount": 2,
                  "targetCategories": [
                    {
                      "id": ${rent.id},
                      "name": "Rent"
                    },
                    {
                      "id": ${travel.id},
                      "name": "Travel"
                    }
                  ]
                }
            """.trimIndent(),
        )
    }

    @Test
    fun mergesExpenseCategoryIntoTargetCategory() {
        val alice = saveUser("alice", UserType.USER)
        val groceries = saveCategory(alice, "Groceries")
        val food = saveCategory(alice, "Food")
        val incomeCategory = saveIncomeCategory(alice)
        val account = saveAccount(alice)
        val movedExpense = saveTransaction(alice, account, groceries, TransactionType.EXPENSE, 1_000)
        val existingExpense = saveTransaction(alice, account, food, TransactionType.EXPENSE, 2_000)
        val income = saveTransaction(alice, account, incomeCategory, TransactionType.INCOME, 3_000)
        val token = api().login("alice", "password")

        val response = api().postJson(
            "/api/tracking/expense-categories/${groceries.id}/merge",
            """
                {"targetCategoryId": ${food.id}}
            """.trimIndent(),
            token,
        )

        response.statusCode().shouldBe(204)
        expenseCategoryRepository.findById(groceries.id!!).isPresent.shouldBe(false)
        transactionRepository.findById(movedExpense.id!!).get().categoryId.shouldBe(food.id)
        transactionRepository.findById(existingExpense.id!!).get().categoryId.shouldBe(food.id)
        transactionRepository.findById(income.id!!).get().categoryId.shouldBe(incomeCategory.id)
    }

    @Test
    fun rejectsInvalidExpenseCategoryMerges() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val groceries = saveCategory(alice, "Groceries")
        val food = saveCategory(alice, "Food")
        val bobCategory = saveCategory(bob, "Bob category")
        val token = api().login("alice", "password")

        api().get("/api/tracking/expense-categories/${groceries.id}/merge-summary", null).statusCode().shouldBe(401)
        api().get("/api/tracking/expense-categories/${bobCategory.id}/merge-summary", token).statusCode().shouldBe(404)
        api().postJson(
            "/api/tracking/expense-categories/${groceries.id}/merge",
            """{"targetCategoryId": ${groceries.id}}""",
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/expense-categories/${groceries.id}/merge",
            """{"targetCategoryId": ${bobCategory.id}}""",
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/expense-categories/${bobCategory.id}/merge",
            """{"targetCategoryId": ${food.id}}""",
            token,
        ).statusCode().shouldBe(404)
    }

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = type,
        ),
    )

    private fun saveCategory(user: User, name: String): ExpenseCategory = expenseCategoryRepository.save(
        ExpenseCategory(
            userId = user.id!!,
            name = name,
        ),
    )

    private fun saveIncomeCategory(user: User): IncomeCategory = incomeCategoryRepository.save(
        IncomeCategory(
            userId = user.id!!,
            name = "Salary",
        ),
    )

    private fun saveAccount(user: User): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = "Main ${user.username}",
            currency = "AUD",
            initialBalanceMinor = 0,
            isDefault = true,
        ),
    )

    private fun saveTransaction(
        user: User,
        account: TrackingAccount,
        category: Any,
        type: TransactionType,
        amountMinor: Long,
    ): Transaction = transactionRepository.save(
        Transaction(
            userId = user.id!!,
            type = type,
            trackingAccountId = account.id!!,
            categoryId = when (category) {
                is ExpenseCategory -> category.id!!
                is IncomeCategory -> category.id!!
                else -> error("Unexpected category type")
            },
            date = LocalDate.of(2099, 6, 1),
            amountMinor = amountMinor,
        ),
    )
}
