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
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class DashboardChartPresetApiTest : IntegrationTestSupport() {
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

    @Test
    fun requiresRegularUserAndReturnsAnExplicitEmptyList() {
        saveUser("alice", UserType.USER)
        saveUser("admin", UserType.ADMIN)
        val adminToken = api().login("admin", "password")
        val userToken = api().login("alice", "password")

        api().get("/api/tracking/dashboard/chart-presets", null).statusCode().shouldBe(401)
        api().get("/api/tracking/dashboard/chart-presets", adminToken).statusCode().shouldBe(403)
        val request = presetJson("View", "INCLUDE", "[]", "INCLUDE", "[]", "AUTO")
        api().postJson("/api/tracking/dashboard/chart-presets/EXPENSE", request, null).statusCode().shouldBe(401)
        api().postJson("/api/tracking/dashboard/chart-presets/EXPENSE", request, adminToken).statusCode().shouldBe(403)
        api().patchJson("/api/tracking/dashboard/chart-presets/EXPENSE/1", request, null).statusCode().shouldBe(401)
        api().postJson(
            "/api/tracking/dashboard/chart-presets/EXPENSE/active",
            """{ "presetId": null }""",
            adminToken,
        ).statusCode().shouldBe(403)
        api().delete("/api/tracking/dashboard/chart-presets/EXPENSE/1", null).statusCode().shouldBe(401)

        val response = api().get("/api/tracking/dashboard/chart-presets", userToken)
        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson("""{ "presets": [] }""")
    }

    @Test
    fun managesPresetsAndPersistsOneActivePresetPerChart() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main")
        val savings = saveAccount(alice, "Savings")
        val taxes = saveExpenseCategory(alice, "Taxes")
        val groceries = saveExpenseCategory(alice, "Groceries")
        val salary = saveIncomeCategory(alice, "Salary")
        val token = api().login("alice", "password")

        val expenseResponse = api().postJson(
            "/api/tracking/dashboard/chart-presets/EXPENSE",
            presetJson(
                name = "  Discretionary  ",
                categoryMode = "EXCLUDE",
                categoryIds = "[${taxes.id}, ${taxes.id}]",
                accountMode = "INCLUDE",
                accountIds = "[${main.id}, ${savings.id}]",
                granularity = "MONTH",
            ),
            token,
        )
        expenseResponse.statusCode().shouldBe(201)
        expenseResponse.body().shouldEqualJson(
            """
                {
                  "id": 1,
                  "name": "Discretionary",
                  "transactionType": "EXPENSE",
                  "categoryFilterMode": "EXCLUDE",
                  "categoryIds": [${taxes.id}],
                  "accountFilterMode": "INCLUDE",
                  "accountIds": [${main.id}, ${savings.id}],
                  "granularity": "MONTH",
                  "isActive": true
                }
            """.trimIndent(),
        )
        val incomeResponse = api().postJson(
            "/api/tracking/dashboard/chart-presets/INCOME",
            presetJson("Salary only", "INCLUDE", "[${salary.id}]", "EXCLUDE", "[]", "AUTO"),
            token,
        )
        incomeResponse.statusCode().shouldBe(201)

        val activeResponse = api().get("/api/tracking/dashboard/chart-presets", token)
        activeResponse.statusCode().shouldBe(200)
        activeResponse.body().shouldEqualJson(
            """
                {
                  "presets": [
                    {
                      "id": 1,
                      "name": "Discretionary",
                      "transactionType": "EXPENSE",
                      "categoryFilterMode": "EXCLUDE",
                      "categoryIds": [${taxes.id}],
                      "accountFilterMode": "INCLUDE",
                      "accountIds": [${main.id}, ${savings.id}],
                      "granularity": "MONTH",
                      "isActive": true
                    },
                    {
                      "id": 2,
                      "name": "Salary only",
                      "transactionType": "INCOME",
                      "categoryFilterMode": "INCLUDE",
                      "categoryIds": [${salary.id}],
                      "accountFilterMode": "EXCLUDE",
                      "accountIds": [],
                      "granularity": "AUTO",
                      "isActive": true
                    }
                  ]
                }
            """.trimIndent(),
        )

        val updateResponse = api().patchJson(
            "/api/tracking/dashboard/chart-presets/EXPENSE/1",
            presetJson("Everyday", "INCLUDE", "[${groceries.id}]", "EXCLUDE", "[${savings.id}]", "WEEK"),
            token,
        )
        updateResponse.statusCode().shouldBe(200)
        updateResponse.body().shouldEqualJson(
            """
                {
                  "id": 1,
                  "name": "Everyday",
                  "transactionType": "EXPENSE",
                  "categoryFilterMode": "INCLUDE",
                  "categoryIds": [${groceries.id}],
                  "accountFilterMode": "EXCLUDE",
                  "accountIds": [${savings.id}],
                  "granularity": "WEEK",
                  "isActive": true
                }
            """.trimIndent(),
        )

        api().delete("/api/tracking/dashboard/chart-presets/EXPENSE/1", token).statusCode().shouldBe(204)
        api().postJson(
            "/api/tracking/dashboard/chart-presets/INCOME/active",
            """{ "presetId": null }""",
            token,
        ).statusCode().shouldBe(204)

        api().get("/api/tracking/dashboard/chart-presets", token).body().shouldEqualJson(
            """
                {
                  "presets": [
                    {
                      "id": 2,
                      "name": "Salary only",
                      "transactionType": "INCOME",
                      "categoryFilterMode": "INCLUDE",
                      "categoryIds": [${salary.id}],
                      "accountFilterMode": "EXCLUDE",
                      "accountIds": [],
                      "granularity": "AUTO",
                      "isActive": false
                    }
                  ]
                }
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsInvalidReferencesAndIsolatesPresetMutations() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val aliceAccount = saveAccount(alice, "Main")
        val aliceExpense = saveExpenseCategory(alice, "General")
        val bobAccount = saveAccount(bob, "Bob account")
        val bobExpense = saveExpenseCategory(bob, "Bob category")
        val aliceToken = api().login("alice", "password")
        val bobToken = api().login("bob", "password")

        api().postJson(
            "/api/tracking/dashboard/chart-presets/EXPENSE",
            presetJson("Invalid account", "INCLUDE", "[${aliceExpense.id}]", "INCLUDE", "[${bobAccount.id}]", "AUTO"),
            aliceToken,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/dashboard/chart-presets/EXPENSE",
            presetJson("Invalid category", "INCLUDE", "[${bobExpense.id}]", "INCLUDE", "[${aliceAccount.id}]", "AUTO"),
            aliceToken,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/dashboard/chart-presets/EXPENSE",
            presetJson("", "INCLUDE", "[]", "INCLUDE", "[]", "AUTO"),
            aliceToken,
        ).statusCode().shouldBe(400)

        val bobPreset = api().postJson(
            "/api/tracking/dashboard/chart-presets/EXPENSE",
            presetJson("Bob view", "INCLUDE", "[${bobExpense.id}]", "INCLUDE", "[${bobAccount.id}]", "AUTO"),
            bobToken,
        )
        bobPreset.statusCode().shouldBe(201)

        api().patchJson(
            "/api/tracking/dashboard/chart-presets/EXPENSE/1",
            presetJson("Stolen", "INCLUDE", "[]", "INCLUDE", "[]", "AUTO"),
            aliceToken,
        ).statusCode().shouldBe(404)
        api().postJson(
            "/api/tracking/dashboard/chart-presets/INCOME/active",
            """{ "presetId": 1 }""",
            bobToken,
        ).statusCode().shouldBe(404)
        api().delete("/api/tracking/dashboard/chart-presets/EXPENSE/1", aliceToken).statusCode().shouldBe(404)
    }

    @Test
    fun preservesPresetReferencesWhenCategoriesAndAccountsAreMerged() {
        val alice = saveUser("alice", UserType.USER)
        val main = saveAccount(alice, "Main", isDefault = true)
        val cash = saveAccount(alice, "Cash")
        val groceries = saveExpenseCategory(alice, "Groceries")
        val food = saveExpenseCategory(alice, "Food")
        val wages = saveIncomeCategory(alice, "Wages")
        val salary = saveIncomeCategory(alice, "Salary")
        val token = api().login("alice", "password")

        api().postJson(
            "/api/tracking/dashboard/chart-presets/EXPENSE",
            presetJson(
                "Everyday",
                "INCLUDE",
                "[${groceries.id}, ${food.id}]",
                "INCLUDE",
                "[${cash.id}, ${main.id}]",
                "AUTO",
            ),
            token,
        ).statusCode().shouldBe(201)
        api().postJson(
            "/api/tracking/dashboard/chart-presets/INCOME",
            presetJson(
                "Earnings",
                "EXCLUDE",
                "[${wages.id}, ${salary.id}]",
                "EXCLUDE",
                "[${cash.id}, ${main.id}]",
                "MONTH",
            ),
            token,
        ).statusCode().shouldBe(201)

        api().postJson(
            "/api/tracking/expense-categories/${groceries.id}/merge",
            """{ "targetCategoryId": ${food.id} }""",
            token,
        ).statusCode().shouldBe(204)
        api().postJson(
            "/api/tracking/income-categories/${wages.id}/merge",
            """{ "targetCategoryId": ${salary.id} }""",
            token,
        ).statusCode().shouldBe(204)
        api().postJson(
            "/api/tracking/accounts/${cash.id}/merge",
            """{ "targetAccountId": ${main.id} }""",
            token,
        ).statusCode().shouldBe(204)

        api().get("/api/tracking/dashboard/chart-presets", token).body().shouldEqualJson(
            """
                {
                  "presets": [
                    {
                      "id": 1,
                      "name": "Everyday",
                      "transactionType": "EXPENSE",
                      "categoryFilterMode": "INCLUDE",
                      "categoryIds": [${food.id}],
                      "accountFilterMode": "INCLUDE",
                      "accountIds": [${main.id}],
                      "granularity": "AUTO",
                      "isActive": true
                    },
                    {
                      "id": 2,
                      "name": "Earnings",
                      "transactionType": "INCOME",
                      "categoryFilterMode": "EXCLUDE",
                      "categoryIds": [${salary.id}],
                      "accountFilterMode": "EXCLUDE",
                      "accountIds": [${main.id}],
                      "granularity": "MONTH",
                      "isActive": true
                    }
                  ]
                }
            """.trimIndent(),
        )
    }

    private fun presetJson(
        name: String,
        categoryMode: String,
        categoryIds: String,
        accountMode: String,
        accountIds: String,
        granularity: String,
    ) =
        """
            {
              "name": "$name",
              "categoryFilterMode": "$categoryMode",
              "categoryIds": $categoryIds,
              "accountFilterMode": "$accountMode",
              "accountIds": $accountIds,
              "granularity": "$granularity"
            }
        """.trimIndent()

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(username = username, passwordHash = passwordHasher.hash("password"), type = type),
    )

    private fun saveAccount(user: User, name: String, isDefault: Boolean = false): TrackingAccount =
        trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = "AUD",
            initialBalanceMinor = 0,
            isDefault = isDefault,
        ),
    )

    private fun saveExpenseCategory(user: User, name: String): ExpenseCategory = expenseCategoryRepository.save(
        ExpenseCategory(userId = user.id!!, name = name),
    )

    private fun saveIncomeCategory(user: User, name: String): IncomeCategory = incomeCategoryRepository.save(
        IncomeCategory(userId = user.id!!, name = name),
    )
}
