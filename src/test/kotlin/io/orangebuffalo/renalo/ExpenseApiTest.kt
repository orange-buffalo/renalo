package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestTimeProvider
import io.orangebuffalo.renalo.tracking.Expense
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.ExpenseRepository
import io.orangebuffalo.renalo.tracking.RecurringExpenseRule
import io.orangebuffalo.renalo.tracking.RecurringExpenseRuleRepository
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.LocalDate

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
    lateinit var recurringExpenseRuleRepository: RecurringExpenseRuleRepository

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
        val body = expenseJson(account, category, "2026-06-15", 1234, "Milk")

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
        val older = saveExpense(alice, account, category, "2026-06-14", 1200, "Bread")
        val newer = saveExpense(alice, account, category, "2026-06-15", 3400, null)
        val recurringRule = saveRecurringRule(alice, account, category, "2026-06-16", "2026-07-01")
        val recurring = saveExpense(
            alice,
            account,
            category,
            "2026-06-16",
            5600,
            "Rent",
            recurringRuleId = recurringRule.id,
            recurringInstanceDate = "2026-06-16",
        )
        saveExpense(bob, saveAccount(bob, "Bob account", "USD"), saveCategory(bob, "Bob category"), "2026-06-16", 999, "Hidden")

        val response = api().get("/api/tracking/expenses", api().login("alice", "password"))

        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson(
            """
                [
                  {
                    "id": ${recurring.id},
                    "trackingAccount": {
                      "id": ${account.id},
                      "name": "Everyday",
                      "currency": "AUD"
                    },
                    "category": {
                      "id": ${category.id},
                      "name": "Groceries"
                    },
                    "date": "2026-06-16",
                    "amountMinor": 5600,
                    "notes": "Rent",
                    "recurrence": {
                      "ruleId": ${recurringRule.id},
                      "instanceDate": "2026-06-16",
                      "description": "Repeats weekly until 1 Jul 2026"
                    }
                  },
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
                    "date": "2026-06-15",
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
                    "date": "2026-06-14",
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
            expenseJson(account, groceries, "2026-06-15", 1234, " Milk "),
            token,
        )

        createResponse.statusCode().shouldBe(201)
        val expense = expenseRepository.findByUserIdOrderByDateDesc(alice.id!!).single()
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
                  "date": "2026-06-15",
                  "amountMinor": 1234,
                  "notes": "Milk"
                }
            """.trimIndent(),
        )

        val updateResponse = api().patchJson(
            "/api/tracking/expenses/${expense.id}",
            expenseJson(savings, rent, "2026-06-16", 2000, null),
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
                  "date": "2026-06-16",
                  "amountMinor": 2000
                }
            """.trimIndent(),
        )
        expenseRepository.findById(expense.id!!).get().trackingAccountId.shouldBe(savings.id)

        api().delete("/api/tracking/expenses/${expense.id}", token).statusCode().shouldBe(204)
        expenseRepository.findById(expense.id!!).isPresent.shouldBe(false)
    }

    @Test
    fun createsRecurringExpenseAndImmediatelyGeneratesOccurrences() {
        val alice = saveUser("alice", UserType.USER)
        val account = saveAccount(alice, "Main", "AUD")
        val category = saveCategory(alice, "Rent")
        val token = api().login("alice", "password")

        val createResponse = api().postJson(
            "/api/tracking/expenses",
            recurringExpenseJson(
                account = account,
                category = category,
                date = "2099-06-01",
                amountMinor = 1234,
                notes = " Rent ",
                frequency = 2,
                interval = "WEEK",
                endDate = "2099-06-29",
            ),
            token,
        )

        createResponse.statusCode().shouldBe(201)
        val rule = recurringExpenseRuleRepository.findAll().single()
        val firstExpense = expenseRepository.findByRecurringRuleIdAndRecurringInstanceDate(rule.id!!, LocalDate.parse("2099-06-01"))!!
        createResponse.body().shouldEqualJson(
            """
                {
                  "id": ${firstExpense.id},
                  "trackingAccount": {
                    "id": ${account.id},
                    "name": "Main",
                    "currency": "AUD"
                  },
                  "category": {
                    "id": ${category.id},
                    "name": "Rent"
                  },
                  "date": "2099-06-01",
                  "amountMinor": 1234,
                  "notes": "Rent",
                  "recurrence": {
                    "ruleId": ${rule.id},
                    "instanceDate": "2099-06-01",
                    "description": "Repeats every 2 weeks until 29 Jun 2099"
                  }
                }
            """.trimIndent(),
        )
        rule.userId.shouldBe(alice.id)
        rule.trackingAccountId.shouldBe(account.id)
        rule.categoryId.shouldBe(category.id)
        rule.startDate.shouldBe(LocalDate.parse("2099-06-01"))
        rule.endDate.shouldBe(LocalDate.parse("2099-06-29"))
        rule.recurrenceFrequency.shouldBe(2)
        rule.recurrenceInterval.name.shouldBe("WEEK")
        rule.amountMinor.shouldBe(1234)
        rule.notes.shouldBe("Rent")
        expenseRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!).map { it.recurringInstanceDate }
            .shouldContainExactly(
                LocalDate.parse("2099-06-01"),
                LocalDate.parse("2099-06-15"),
                LocalDate.parse("2099-06-29"),
            )
    }

    @Test
    fun createsInitialRecurringScheduleTypes() {
        val alice = saveUser("alice", UserType.USER)
        val account = saveAccount(alice, "Main", "AUD")
        val category = saveCategory(alice, "General")
        val token = api().login("alice", "password")

        api().postJson(
            "/api/tracking/expenses",
            recurringExpenseJson(account, category, "2099-06-01", 100, null, 1, "DAY", "2099-06-03"),
            token,
        ).statusCode().shouldBe(201)
        api().postJson(
            "/api/tracking/expenses",
            recurringExpenseJson(account, category, "2099-06-04", 100, null, 1, "WEEK", "2099-06-18"),
            token,
        ).statusCode().shouldBe(201)
        api().postJson(
            "/api/tracking/expenses",
            recurringExpenseJson(account, category, "2099-06-05", 100, null, 2, "WEEK", "2099-07-03"),
            token,
        ).statusCode().shouldBe(201)
        api().postJson(
            "/api/tracking/expenses",
            recurringExpenseJson(account, category, "2099-06-30", 100, null, 1, "MONTH", "2099-08-31"),
            token,
        ).statusCode().shouldBe(201)

        val rules = recurringExpenseRuleRepository.findAll().sortedBy { it.startDate }
        rules.map { it.recurrenceFrequency to it.recurrenceInterval.name }
            .shouldContainExactly(
                1 to "DAY",
                1 to "WEEK",
                2 to "WEEK",
                1 to "MONTH",
            )
        rules.map { rule ->
            expenseRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!)
                .map { it.recurringInstanceDate }
        }.shouldContainExactly(
            listOf(
                LocalDate.parse("2099-06-01"),
                LocalDate.parse("2099-06-02"),
                LocalDate.parse("2099-06-03"),
            ),
            listOf(
                LocalDate.parse("2099-06-04"),
                LocalDate.parse("2099-06-11"),
                LocalDate.parse("2099-06-18"),
            ),
            listOf(
                LocalDate.parse("2099-06-05"),
                LocalDate.parse("2099-06-19"),
                LocalDate.parse("2099-07-03"),
            ),
            listOf(
                LocalDate.parse("2099-06-30"),
                LocalDate.parse("2099-07-30"),
                LocalDate.parse("2099-08-30"),
            ),
        )
    }

    @Test
    fun createsRecurringExpensesForPastTodayAndFutureStartDatesWithAndWithoutEndDate() {
        val alice = saveUser("alice", UserType.USER)
        val account = saveAccount(alice, "Main", "AUD")
        val category = saveCategory(alice, "General")
        val token = api().login("alice", "password")

        listOf(
            TestTimeProvider.DEFAULT_DATE.minusWeeks(2),
            TestTimeProvider.DEFAULT_DATE,
            TestTimeProvider.DEFAULT_DATE.plusWeeks(2),
        ).forEach { startDate ->
            listOf(null, startDate.plusWeeks(2)).forEach { endDate ->
                val existingRuleIds = recurringExpenseRuleRepository.findAll().mapNotNull { it.id }.toSet()

                api().postJson(
                    "/api/tracking/expenses",
                    recurringExpenseJson(
                        account = account,
                        category = category,
                        date = startDate.toString(),
                        amountMinor = 100,
                        notes = "Start $startDate end ${endDate ?: "none"}",
                        frequency = 1,
                        interval = "WEEK",
                        endDate = endDate?.toString(),
                    ),
                    token,
                ).statusCode().shouldBe(201)

                val rule = recurringExpenseRuleRepository.findAll().single { it.id !in existingRuleIds }
                rule.startDate.shouldBe(startDate)
                rule.endDate.shouldBe(endDate)
                val generatedDates = expenseRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!)
                    .map { it.recurringInstanceDate }
                if (endDate == null) {
                    generatedDates.take(3).shouldContainExactly(
                        startDate,
                        startDate.plusWeeks(1),
                        startDate.plusWeeks(2),
                    )
                    generatedDates.last()!!.isAfter(TestTimeProvider.DEFAULT_DATE.plusYears(1)).shouldBe(false)
                } else {
                    generatedDates.shouldContainExactly(
                        startDate,
                        startDate.plusWeeks(1),
                        endDate,
                    )
                }
            }
        }
    }

    @Test
    fun rejectsInvalidAndOtherUsersExpenseReferences() {
        val alice = saveUser("alice", UserType.USER)
        val bob = saveUser("bob", UserType.USER)
        val aliceAccount = saveAccount(alice, "Main", "AUD")
        val aliceCategory = saveCategory(alice, "Groceries")
        val bobAccount = saveAccount(bob, "Bob account", "USD")
        val bobCategory = saveCategory(bob, "Bob category")
        val bobExpense = saveExpense(bob, bobAccount, bobCategory, "2026-06-15", 999, null)
        val token = api().login("alice", "password")

        api().postJson(
            "/api/tracking/expenses",
            expenseJson(aliceAccount, aliceCategory, "2026-06-15", 0, null),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/expenses",
            expenseJson(bobAccount, aliceCategory, "2026-06-15", 1000, null),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/expenses",
            expenseJson(aliceAccount, bobCategory, "2026-06-15", 1000, null),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/expenses",
            recurringExpenseJson(aliceAccount, aliceCategory, "2026-06-15", 1000, null, 0, "WEEK", "2026-06-22"),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/expenses",
            recurringExpenseJson(aliceAccount, aliceCategory, "2026-06-15", 1000, null, 1, "WEEK", "2026-06-14"),
            token,
        ).statusCode().shouldBe(400)
        api().postJson(
            "/api/tracking/expenses",
            recurringExpenseJson(bobAccount, aliceCategory, "2026-06-15", 1000, null, 1, "WEEK", "2026-06-22"),
            token,
        ).statusCode().shouldBe(400)
        api().get("/api/tracking/expenses/${bobExpense.id}", token).statusCode().shouldBe(404)
        api().patchJson(
            "/api/tracking/expenses/${bobExpense.id}",
            expenseJson(aliceAccount, aliceCategory, "2026-06-15", 1000, null),
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
        date: String,
        amountMinor: Long,
        notes: String?,
        recurringRuleId: Long? = null,
        recurringInstanceDate: String? = null,
    ): Expense = expenseRepository.save(
        Expense(
            userId = user.id!!,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            date = LocalDate.parse(date),
            amountMinor = amountMinor,
            notes = notes,
            recurringRuleId = recurringRuleId,
            recurringInstanceDate = recurringInstanceDate?.let { LocalDate.parse(it) },
        ),
    )

    private fun saveRecurringRule(
        user: User,
        account: TrackingAccount,
        category: ExpenseCategory,
        startDate: String,
        endDate: String?,
    ): RecurringExpenseRule = recurringExpenseRuleRepository.save(
        RecurringExpenseRule(
            userId = user.id!!,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            startDate = LocalDate.parse(startDate),
            endDate = endDate?.let { LocalDate.parse(it) },
            recurrenceFrequency = 1,
            recurrenceInterval = RecurrenceInterval.WEEK,
            generatedUntil = LocalDate.parse(startDate),
            amountMinor = 5600,
            notes = "Rent",
        ),
    )

    private fun expenseJson(
        account: TrackingAccount,
        category: ExpenseCategory,
        date: String,
        amountMinor: Long,
        notes: String?,
    ) = """
        {
          "trackingAccountId": ${account.id},
          "categoryId": ${category.id},
          "date": "$date",
          "amountMinor": $amountMinor,
          "notes": ${notes?.let { "\"$it\"" } ?: "null"}
        }
    """.trimIndent()

    private fun recurringExpenseJson(
        account: TrackingAccount,
        category: ExpenseCategory,
        date: String,
        amountMinor: Long,
        notes: String?,
        frequency: Int,
        interval: String,
        endDate: String?,
    ) = """
        {
          "trackingAccountId": ${account.id},
          "categoryId": ${category.id},
          "date": "$date",
          "amountMinor": $amountMinor,
          "notes": ${notes?.let { "\"$it\"" } ?: "null"},
          "recurrence": {
            "frequency": $frequency,
            "interval": "$interval",
            "endDate": ${endDate?.let { "\"$it\"" } ?: "null"}
          }
        }
    """.trimIndent()
}
