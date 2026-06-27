package io.orangebuffalo.renalo

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestAuthTokens
import io.orangebuffalo.renalo.test.TestTimeProvider
import io.orangebuffalo.renalo.test.shouldEventually
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
class ExpensesPagePlaywrightTest : IntegrationTestSupport() {
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

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun managesExpensesFromExpensesPage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        saveAccount(alice, "Travel", "EUR", isDefault = false)
        val groceries = saveCategory(alice, "Groceries")
        val rent = saveCategory(alice, "Rent")
        val todayExpense = saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE, 1234, "Milk")
        saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE.minusDays(1), 5500, null)
        val recurringRule = saveRecurringRule(alice, main, rent)
        saveExpense(
            alice,
            main,
            rent,
            TestTimeProvider.DEFAULT_DATE,
            2500,
            "Subscription",
            recurringRule = recurringRule,
        )
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/expenses")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$12.34", "Today", "Main", "Milk", "edit delete"),
            ExpenseRow("Rent", "A$25.00", "Today Repeats weekly until 21 Jun 2099", "Main", "Subscription", "edit delete"),
            ExpenseRow("Groceries", "A$55.00", "Yesterday", "Main", "-", "edit delete"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add expense")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()
        selectOption(page, "Category", "Rent")
        page.locator("input[name='amount']").fill("42")
        page.getByLabel("Notes").fill("Weekly rent")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create expense")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        val rentExpense = expenseRepository.findByUserIdOrderByDateDesc(alice.id!!).first { it.notes == "Weekly rent" }
        rentExpense.amountMinor.shouldBe(4200)
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$12.34", "Today", "Main", "Milk", "edit delete"),
            ExpenseRow("Rent", "A$25.00", "Today Repeats weekly until 21 Jun 2099", "Main", "Subscription", "edit delete"),
            ExpenseRow("Rent", "A$42.00", "Today", "Main", "Weekly rent", "edit delete"),
            ExpenseRow("Groceries", "A$55.00", "Yesterday", "Main", "-", "edit delete"),
        )

        page.locator("[data-testid='expense-row-${todayExpense.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Edit Groceries expense"))
            .click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Edit expense"))).isVisible()
        selectOption(page, "Category", "Rent")
        page.locator("input[name='amount']").fill("19")
        page.getByLabel("Notes").fill("Lunch")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save expense")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        expenseRepository.findById(todayExpense.id!!).get().amountMinor.shouldBe(1900)
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Rent", "A$19.00", "Today", "Main", "Lunch", "edit delete"),
            ExpenseRow("Rent", "A$25.00", "Today Repeats weekly until 21 Jun 2099", "Main", "Subscription", "edit delete"),
            ExpenseRow("Rent", "A$42.00", "Today", "Main", "Weekly rent", "edit delete"),
            ExpenseRow("Groceries", "A$55.00", "Yesterday", "Main", "-", "edit delete"),
        )

        page.locator("[data-testid='expense-row-${todayExpense.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Delete Rent expense"))
            .click()
        assertThat(page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Delete Rent expense?"))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Delete expense")).click()

        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Rent", "A$25.00", "Today Repeats weekly until 21 Jun 2099", "Main", "Subscription", "edit delete"),
            ExpenseRow("Rent", "A$42.00", "Today", "Main", "Weekly rent", "edit delete"),
            ExpenseRow("Groceries", "A$55.00", "Yesterday", "Main", "-", "edit delete"),
        )
        expenseRepository.findById(todayExpense.id!!).isPresent.shouldBe(false)
    }

    @Test
    fun showsExpenseMobileCardsWithDateAlwaysVisibleAndDetailsExpandable(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val groceries = saveCategory(alice, "Groceries")
        val expense = saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE, 1234, "Milk")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.setViewportSize(390, 844)

        page.navigate(server.url.toString() + "/expenses")

        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$12.34", "Today", "Main", "Milk", "edit delete"),
        )

        val expenseCard = page.locator("[data-testid='expense-row-${expense.id}']")
        assertThat(expenseCard.getByText("Groceries")).isVisible()
        assertThat(expenseCard.getByText("A$12.34")).isVisible()
        assertThat(expenseCard.getByText("Today")).isVisible()
        assertThat(expenseCard.getByText("Main")).not().isVisible()
        assertThat(expenseCard.getByText("Milk")).not().isVisible()
        expenseCard.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Show Groceries details")).click()

        assertThat(expenseCard.getByText("Main")).isVisible()
        assertThat(expenseCard.getByText("Milk")).isVisible()
        expenseCard.locator("[data-mobile-label='Account']")
            .evaluate("element => getComputedStyle(element, '::before').content")
            .shouldBe("\"Account\"")
    }

    @Test
    fun createsRecurringExpenseFromExpenseForm(page: Page) {
        val alice = saveUser("alice")
        saveAccount(alice, "Main", "AUD", isDefault = true)
        saveCategory(alice, "Rent")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/expenses/create")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()
        selectOption(page, "Category", "Rent")
        page.locator("input[name='amount']").fill("25")
        page.getByLabel("Notes").fill("Gym membership")
        page.getByLabel("Recurring expense").press("Space")
        page.getByLabel("Repeat").click()
        assertThat(page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName("Daily").setExact(true))).isVisible()
        assertThat(page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName("Weekly").setExact(true))).isVisible()
        assertThat(page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName("Biweekly").setExact(true))).isVisible()
        assertThat(page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName("Monthly").setExact(true))).isVisible()
        page.keyboard().press("Escape")
        selectOption(page, "Repeat", "Biweekly")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create expense")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        val rule = recurringExpenseRuleRepository.findAll().single()
        rule.recurrenceFrequency.shouldBe(2)
        rule.recurrenceInterval.shouldBe(RecurrenceInterval.WEEK)
        rule.endDate.shouldBe(null)
        expenseRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!).first().notes.shouldBe("Gym membership")
        assertThat(page.getByText("Repeats every 2 weeks").first()).isVisible()
    }

    @Test
    fun showsSharedEmptyStateWhenNoExpensesExist(page: Page) {
        saveUser("alice")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/expenses")

        assertThat(page.getByText("No expenses found")).isVisible()
        assertThat(page.locator(".table-empty-state-icon")).isVisible()
    }

    private fun saveUser(username: String): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = UserType.USER,
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
        date: LocalDate,
        amountMinor: Long,
        notes: String?,
        recurringRule: RecurringExpenseRule? = null,
    ): Expense = expenseRepository.save(
        Expense(
            userId = user.id!!,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            date = date,
            amountMinor = amountMinor,
            notes = notes,
            recurringRuleId = recurringRule?.id,
            recurringInstanceDate = recurringRule?.let { date },
        ),
    )

    private fun saveRecurringRule(user: User, account: TrackingAccount, category: ExpenseCategory): RecurringExpenseRule =
        recurringExpenseRuleRepository.save(
            RecurringExpenseRule(
                userId = user.id!!,
                trackingAccountId = account.id!!,
                categoryId = category.id!!,
                startDate = TestTimeProvider.DEFAULT_DATE,
                endDate = TestTimeProvider.DEFAULT_DATE.plusWeeks(1),
                recurrenceFrequency = 1,
                recurrenceInterval = RecurrenceInterval.WEEK,
                generatedUntil = TestTimeProvider.DEFAULT_DATE,
                amountMinor = 2500,
                notes = "Subscription",
            ),
        )

    private fun selectOption(page: Page, label: String, option: String) {
        page.getByLabel(label).click()
        page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName(option)).click()
    }

    private fun extractExpenseRows(page: Page): List<ExpenseRow> {
        @Suppress("UNCHECKED_CAST")
        val rows = page.locator("[data-testid^='expense-row-']").evaluateAll(
            """
                rows => rows.map(row => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'))
                    .map(cell => {
                        const actions = Array.from(cell.querySelectorAll('[data-action-icon]'))
                            .map(icon => icon.dataset.actionIcon);
                        return actions.length ? actions.join(' ') : cell.innerText.trim().replace(/\n+/g, ' ');
                    }))
            """.trimIndent(),
        ) as List<List<String>>

        return rows.map { cells ->
            ExpenseRow(
                category = cells.getOrElse(0) { "" },
                amount = cells.getOrElse(1) { "" },
                date = cells.getOrElse(2) { "" },
                account = cells.getOrElse(3) { "" },
                notes = cells.getOrElse(4) { "" },
                action = cells.getOrElse(5) { "" },
            )
        }
    }

    private fun Page.shouldEventuallyContainExpenseRows(vararg expectedRows: ExpenseRow) {
        shouldEventually {
            extractExpenseRows(this).shouldContainExactlyInAnyOrder(*expectedRows)
        }
    }

    private data class ExpenseRow(
        val category: String,
        val amount: String,
        val date: String,
        val account: String,
        val notes: String,
        val action: String,
    )
}
