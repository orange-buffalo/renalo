package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class ExpenseCategoryApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

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
        val token = api().login("alice", "password")

        val createResponse = api().postJson(
            "/api/tracking/expense-categories",
            """
                {"name":" Groceries "}
            """.trimIndent(),
            token,
        )

        createResponse.statusCode().shouldBe(201)
        val groceries = expenseCategoryRepository.findByUserIdOrderByName(alice.id!!).single()
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
}
