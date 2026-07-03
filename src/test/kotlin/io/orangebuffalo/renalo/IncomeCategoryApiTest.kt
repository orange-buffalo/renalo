package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.IncomeCategory
import io.orangebuffalo.renalo.tracking.IncomeCategoryRepository
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
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
class IncomeCategoryApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var incomeCategoryRepository: IncomeCategoryRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun requiresRegularUserForIncomeCategories() {
        saveUser("alice", UserType.USER)
        saveUser("admin", UserType.ADMIN)
        val userToken = api().login("alice", "password")
        val adminToken = api().login("admin", "password")
        val body = """
            {"name":"Salary"}
        """.trimIndent()

        api().get("/api/tracking/income-categories", null).statusCode().shouldBe(401)
        api().get("/api/tracking/income-categories", adminToken).statusCode().shouldBe(403)
        api().postJson("/api/tracking/income-categories", body, null).statusCode().shouldBe(401)
        api().postJson("/api/tracking/income-categories", body, adminToken).statusCode().shouldBe(403)
        api().get("/api/tracking/income-categories", userToken).statusCode().shouldBe(200)
    }

    @Test
    fun listsOnlyCurrentUsersIncomeCategories() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val salary = saveCategory(alice, "Salary")
        val interest = saveCategory(alice, "Interest")
        saveCategory(bob, "Bob category")

        val response = api().get("/api/tracking/income-categories", api().login("alice", "password"))

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${interest.id},
                    "name": "Interest",
                    "archived": false
                  },
                  {
                    "id": ${salary.id},
                    "name": "Salary",
                    "archived": false
                  }
                ]
            """.trimIndent(),
        )
    }

    @Test
    fun hidesArchivedIncomeCategoriesUnlessRequested() {
        val alice = saveUser("alice", UserType.USER)
        val salary = saveCategory(alice, "Salary")
        val oldCategory = saveCategory(alice, "Old", archived = true)
        val token = api().login("alice", "password")

        val activeResponse = api().get("/api/tracking/income-categories", token)
        val allResponse = api().get("/api/tracking/income-categories?includeArchived=true", token)

        activeResponse.statusCode().shouldBe(200)
        activeResponse.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${salary.id},
                    "name": "Salary",
                    "archived": false
                  }
                ]
            """.trimIndent(),
        )
        allResponse.statusCode().shouldBe(200)
        allResponse.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${oldCategory.id},
                    "name": "Old",
                    "archived": true
                  },
                  {
                    "id": ${salary.id},
                    "name": "Salary",
                    "archived": false
                  }
                ]
            """.trimIndent(),
        )
    }

    @Test
    fun createsAndUpdatesIncomeCategoryForCurrentUser() {
        val alice = saveUser("alice", UserType.USER)
        saveCategory(alice, "General")
        val token = api().login("alice", "password")

        val createResponse = api().postJson(
            "/api/tracking/income-categories",
            """
                {"name":" Salary "}
            """.trimIndent(),
            token,
        )

        createResponse.statusCode().shouldBe(201)
        val salary = incomeCategoryRepository.findByUserIdOrderByName(alice.id!!).first { it.name == "Salary" }
        createResponse.body().shouldEqualJson(
            """
                {
                  "id": ${salary.id},
                  "name": "Salary",
                  "archived": false
                }
            """.trimIndent(),
        )

        val updateResponse = api().patchJson(
            "/api/tracking/income-categories/${salary.id}",
            """
                {"name":"Payroll"}
            """.trimIndent(),
            token,
        )

        updateResponse.statusCode().shouldBe(200)
        updateResponse.body().shouldEqualJson(
            """
                {
                  "id": ${salary.id},
                  "name": "Payroll",
                  "archived": false
                }
            """.trimIndent(),
        )
        incomeCategoryRepository.findById(salary.id!!).get().name.shouldBe("Payroll")
    }

    @Test
    fun createsDefaultIncomeCategoryForNewRegularUser() {
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
        val categories = incomeCategoryRepository.findByUserIdOrderByName(alice.id!!)
        categories.size.shouldBe(1)
        categories.single().name.shouldBe("General")
    }

    @Test
    fun rejectsInvalidAndOtherUsersIncomeCategories() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val bobCategory = saveCategory(bob, "Bob category")
        val token = api().login("alice", "password")

        api().postJson(
            "/api/tracking/income-categories",
            """
                {"name":""}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(400)
        api().get("/api/tracking/income-categories/${bobCategory.id}", token).statusCode().shouldBe(404)
        api().patchJson(
            "/api/tracking/income-categories/${bobCategory.id}",
            """
                {"name":"Hacked"}
            """.trimIndent(),
            token,
        ).statusCode().shouldBe(404)
        incomeCategoryRepository.findByUserIdOrderByName(alice.id!!).size.shouldBe(0)
    }

    @Test
    fun providesIncomeCategoryMergeSummary() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val salary = saveCategory(alice, "Salary")
        val bonus = saveCategory(alice, "Bonus")
        val interest = saveCategory(alice, "Interest")
        val bobCategory = saveCategory(bob, "Bob category")
        val account = saveAccount(alice)
        val expenseCategory = saveExpenseCategory(alice)
        saveTransaction(alice, account, salary, TransactionType.INCOME, 1_000)
        saveTransaction(alice, account, salary, TransactionType.INCOME, 2_000)
        saveTransaction(alice, account, expenseCategory, TransactionType.EXPENSE, 3_000)
        saveTransaction(bob, saveAccount(bob), bobCategory, TransactionType.INCOME, 4_000)

        val response = api().get(
            "/api/tracking/income-categories/${salary.id}/merge-summary",
            api().login("alice", "password"),
        )

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                {
                  "sourceCategory": {
                    "id": ${salary.id},
                    "name": "Salary",
                    "archived": false
                  },
                  "incomesCount": 2,
                  "targetCategories": [
                    {
                      "id": ${bonus.id},
                      "name": "Bonus",
                      "archived": false
                    },
                    {
                      "id": ${interest.id},
                      "name": "Interest",
                      "archived": false
                    }
                  ]
                }
            """.trimIndent(),
        )
    }

    @Test
    fun mergesIncomeCategoryIntoTargetCategory() {
        val alice = saveUser("alice", UserType.USER)
        val salary = saveCategory(alice, "Salary")
        val payroll = saveCategory(alice, "Payroll")
        val expenseCategory = saveExpenseCategory(alice)
        val account = saveAccount(alice)
        val movedIncome = saveTransaction(alice, account, salary, TransactionType.INCOME, 1_000)
        val existingIncome = saveTransaction(alice, account, payroll, TransactionType.INCOME, 2_000)
        val expense = saveTransaction(alice, account, expenseCategory, TransactionType.EXPENSE, 3_000)
        val token = api().login("alice", "password")

        val response = api().postJson(
            "/api/tracking/income-categories/${salary.id}/merge",
            """
                {"targetCategoryId": ${payroll.id}}
            """.trimIndent(),
            token,
        )

        response.statusCode().shouldBe(204)
        incomeCategoryRepository.findById(salary.id!!).isPresent.shouldBe(false)
        transactionRepository.findById(movedIncome.id!!).get().categoryId.shouldBe(payroll.id)
        transactionRepository.findById(existingIncome.id!!).get().categoryId.shouldBe(payroll.id)
        transactionRepository.findById(expense.id!!).get().categoryId.shouldBe(expenseCategory.id)
    }

    @Test
    fun rejectsInvalidIncomeCategoryMerges() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val salary = saveCategory(alice, "Salary")
        val payroll = saveCategory(alice, "Payroll")
        val bobCategory = saveCategory(bob, "Bob category")
        val token = api().login("alice", "password")

        api().get("/api/tracking/income-categories/${salary.id}/merge-summary", null).statusCode().shouldBe(401)
        api().get("/api/tracking/income-categories/${bobCategory.id}/merge-summary", token).statusCode().shouldBe(404)
        api().postJson(
            "/api/tracking/income-categories/${salary.id}/merge",
            """{"targetCategoryId": ${salary.id}}""",
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/income-categories/${salary.id}/merge",
            """{"targetCategoryId": ${bobCategory.id}}""",
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/income-categories/${bobCategory.id}/merge",
            """{"targetCategoryId": ${payroll.id}}""",
            token,
        ).statusCode().shouldBe(404)
    }

    @Test
    fun archivesAndUnarchivesIncomeCategories() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val salary = saveCategory(alice, "Salary")
        val bobCategory = saveCategory(bob, "Bob category")
        val token = api().login("alice", "password")

        api().postJson("/api/tracking/income-categories/${salary.id}/archive", "{}", null).statusCode().shouldBe(401)
        api().postJson("/api/tracking/income-categories/${bobCategory.id}/archive", "{}", token).statusCode().shouldBe(404)

        val archiveResponse = api().postJson(
            "/api/tracking/income-categories/${salary.id}/archive",
            "{}",
            token,
        )

        archiveResponse.statusCode().shouldBe(200)
        archiveResponse.body().shouldEqualJson(
            """
                {
                  "id": ${salary.id},
                  "name": "Salary",
                  "archived": true
                }
            """.trimIndent(),
        )
        incomeCategoryRepository.findById(salary.id!!).get().archived.shouldBe(true)

        val unarchiveResponse = api().postJson(
            "/api/tracking/income-categories/${salary.id}/unarchive",
            "{}",
            token,
        )

        unarchiveResponse.statusCode().shouldBe(200)
        unarchiveResponse.body().shouldEqualJson(
            """
                {
                  "id": ${salary.id},
                  "name": "Salary",
                  "archived": false
                }
            """.trimIndent(),
        )
        incomeCategoryRepository.findById(salary.id!!).get().archived.shouldBe(false)
    }

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = type,
        ),
    )

    private fun saveCategory(user: User, name: String, archived: Boolean = false): IncomeCategory = incomeCategoryRepository.save(
        IncomeCategory(
            userId = user.id!!,
            name = name,
            archived = archived,
        ),
    )

    private fun saveExpenseCategory(user: User): ExpenseCategory = expenseCategoryRepository.save(
        ExpenseCategory(
            userId = user.id!!,
            name = "Groceries",
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
