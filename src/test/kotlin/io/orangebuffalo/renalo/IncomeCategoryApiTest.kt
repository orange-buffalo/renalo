package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.tracking.IncomeCategory
import io.orangebuffalo.renalo.tracking.IncomeCategoryRepository
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class IncomeCategoryApiTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var incomeCategoryRepository: IncomeCategoryRepository

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
                    "name": "Interest"
                  },
                  {
                    "id": ${salary.id},
                    "name": "Salary"
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
                  "name": "Salary"
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
                  "name": "Payroll"
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

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = type,
        ),
    )

    private fun saveCategory(user: User, name: String): IncomeCategory = incomeCategoryRepository.save(
        IncomeCategory(
            userId = user.id!!,
            name = name,
        ),
    )
}
