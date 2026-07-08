package io.orangebuffalo.renalo

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestAuthTokens
import io.orangebuffalo.renalo.test.TestTimeProvider
import io.orangebuffalo.renalo.test.shouldEventually
import io.orangebuffalo.renalo.tracking.Transaction
import io.orangebuffalo.renalo.tracking.TransactionType
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.TransactionRepository
import io.orangebuffalo.renalo.tracking.RecurringTransactionRule
import io.orangebuffalo.renalo.tracking.RecurringTransactionRuleRepository
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.regex.Pattern

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
    lateinit var expenseRepository: TransactionRepository

    @Inject
    lateinit var recurringExpenseRuleRepository: RecurringTransactionRuleRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun managesExpensesFromExpensesPage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        saveAccount(alice, "Travel", "EUR", isDefault = false)
        saveAccount(alice, "Old", "AUD", isDefault = false, archived = true)
        val groceries = saveCategory(alice, "Groceries")
        val rent = saveCategory(alice, "Rent")
        saveCategory(alice, "Old category", archived = true)
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
        val endedRecurringRule = saveRecurringRule(
            alice,
            main,
            rent,
            notes = "Ended subscription",
            startDate = TestTimeProvider.DEFAULT_DATE.minusWeeks(1),
            endDate = TestTimeProvider.DEFAULT_DATE,
        )
        saveExpense(
            alice,
            main,
            rent,
            TestTimeProvider.DEFAULT_DATE.minusWeeks(1),
            1800,
            "Ended subscription",
            recurringRule = endedRecurringRule,
        )
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/expenses")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$12.34", "Today", "Main", "Milk", "edit delete"),
            ExpenseRow("Rent", "A$25.00", "Today Repeats weekly until 21 Jun 2099", "Main", "Subscription", "edit delete"),
            ExpenseRow("Groceries", "A$55.00", "Yesterday", "Main", "-", "edit delete"),
            ExpenseRow("Rent", "A$18.00", "Jun 7", "Main", "Ended subscription", "edit delete"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add expense")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()
        page.locator("form").getByLabel("Account").click()
        assertThat(dropdownOption(page, "Main")).isVisible()
        assertThat(dropdownOption(page, "Old")).not().isVisible()
        page.keyboard().press("Escape")
        page.locator("form").getByLabel("Category").click()
        assertThat(dropdownOption(page, "Rent")).isVisible()
        assertThat(dropdownOption(page, "Old category")).not().isVisible()
        page.keyboard().press("Escape")
        selectCategoryOption(page, "Category", "Rent")
        page.locator("input[name='amount']").fill("42")
        page.getByLabel("Notes").fill("Weekly rent")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create expense")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        val rentExpense = expenseRepository.findByUserIdAndTypeOrderByDateDesc(alice.id!!, TransactionType.EXPENSE).first { it.notes == "Weekly rent" }
        rentExpense.amountMinor.shouldBe(4200)
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$12.34", "Today", "Main", "Milk", "edit delete"),
            ExpenseRow("Rent", "A$25.00", "Today Repeats weekly until 21 Jun 2099", "Main", "Subscription", "edit delete"),
            ExpenseRow("Rent", "A$42.00", "Today", "Main", "Weekly rent", "edit delete"),
            ExpenseRow("Groceries", "A$55.00", "Yesterday", "Main", "-", "edit delete"),
            ExpenseRow("Rent", "A$18.00", "Jun 7", "Main", "Ended subscription", "edit delete"),
        )

        page.locator("[data-testid='expense-row-${todayExpense.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Edit Groceries expense"))
            .click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Edit expense"))).isVisible()
        selectCategoryOption(page, "Category", "Rent")
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
            ExpenseRow("Rent", "A$18.00", "Jun 7", "Main", "Ended subscription", "edit delete"),
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
            ExpenseRow("Rent", "A$18.00", "Jun 7", "Main", "Ended subscription", "edit delete"),
        )
        expenseRepository.findById(todayExpense.id!!).isPresent.shouldBe(false)
    }

    @Test
    fun validatesExpenseFormRequiredFieldsAndAllowsOptionalNotes(page: Page) {
        val alice = saveUser("alice")
        saveAccount(alice, "Main", "AUD", isDefault = true)
        saveCategory(alice, "Groceries")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/expenses/create")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create expense")).click()

        assertThat(page.getByText("Choose a category.")).isVisible()
        assertThat(page.getByText("Enter a valid amount greater than zero.")).isVisible()
        assertRequiredLabel(page, "Amount")

        selectCategoryOption(page, "Category", "Groceries")
        page.locator("input[name='amount']").fill("12.34")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create expense")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$12.34", "Today", "Main", "-", "edit delete"),
        )
        expenseRepository.findByUserIdAndTypeOrderByDateDesc(alice.id!!, TransactionType.EXPENSE)
            .single()
            .notes
            .shouldBe(null)
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

        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("More filters"))).isVisible()
        assertThat(page.getByText("More filters", Page.GetByTextOptions().setExact(true))).not().isVisible()
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Expenses"))).isVisible()

        val expenseCard = page.locator("[data-testid='expense-row-${expense.id}']")
        assertThat(expenseCard).isVisible()
        assertThat(expenseCard.getByText("Groceries")).isVisible()
        assertThat(expenseCard.getByText("A$12.34")).isVisible()
        assertThat(expenseCard.getByText("Today")).isVisible()
        assertThat(expenseCard.getByText("Main")).not().isVisible()
        assertThat(expenseCard.getByText("Milk")).not().isVisible()
        assertThat(expenseCard.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Edit Groceries expense"))).not().isVisible()
        expenseCard.click()

        assertThat(expenseCard.getByText("Main")).isVisible()
        assertThat(expenseCard.getByText("Milk")).isVisible()
        assertThat(expenseCard.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Edit Groceries expense"))).isVisible()
        expenseCard.locator("[data-mobile-label='Account']")
            .evaluate("element => getComputedStyle(element, '::before').content")
            .shouldBe("\"Account\"")
        expenseCard.click()
        assertThat(expenseCard.getByText("Today")).isVisible()
        assertThat(expenseCard.getByText("Main")).not().isVisible()
        assertThat(expenseCard.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Edit Groceries expense"))).not().isVisible()
    }

    @Test
    fun groupsFutureExpensesUntilViewed(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val groceries = saveCategory(alice, "Groceries")
        val rent = saveCategory(alice, "Rent")
        saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE, 1234, "Milk")
        saveExpense(alice, main, rent, TestTimeProvider.DEFAULT_DATE.plusDays(2), 5500, "Planned rent")
        saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE.plusDays(1), 2100, "Planned groceries")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/expenses")

        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Planned expenses", "A$76.00", "", "", "", "view"),
            ExpenseRow("Groceries", "A$12.34", "Today", "Main", "Milk", "edit delete"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("View all planned expenses")).click()
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Rent", "A$55.00", "Jun 16", "Main", "Planned rent", "edit delete"),
            ExpenseRow("Groceries", "A$21.00", "Jun 15", "Main", "Planned groceries", "edit delete"),
            ExpenseRow("Groceries", "A$12.34", "Today", "Main", "Milk", "edit delete"),
        )
    }

    @Test
    fun filtersExpensesBySharedDateRange(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val groceries = saveCategory(alice, "Groceries")
        val rent = saveCategory(alice, "Rent")
        saveExpense(alice, main, rent, LocalDate.parse("2098-12-31"), 3100, "Previous year")
        saveExpense(alice, main, groceries, LocalDate.parse("2099-05-15"), 1500, "Previous month")
        saveExpense(alice, main, groceries, LocalDate.parse("2099-06-03"), 300, "Early month")
        saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE, 1400, "Today")
        saveExpense(alice, main, rent, LocalDate.parse("2099-07-10"), 7000, "Next month")
        saveExpense(alice, main, groceries, LocalDate.parse("2099-04-30"), 4300, "April end")
        saveExpense(alice, main, groceries, LocalDate.parse("2099-04-19"), 1900, "April partial")
        saveExpense(alice, main, rent, LocalDate.parse("2099-03-03"), 3030, "March partial")
        saveExpense(alice, main, rent, LocalDate.parse("2099-03-01"), 3010, "March start")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/expenses")

        assertDateFilterLabel(page, "June 2099")
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$14.00", "Today", "Main", "Today", "edit delete"),
            ExpenseRow("Groceries", "A$3.00", "Jun 3", "Main", "Early month", "edit delete"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Previous date range")).click()
        assertDateFilterLabel(page, "May 2099")
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$15.00", "May 15", "Main", "Previous month", "edit delete"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Next date range")).click()
        assertDateFilterLabel(page, "June 2099")
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$14.00", "Today", "Main", "Today", "edit delete"),
            ExpenseRow("Groceries", "A$3.00", "Jun 3", "Main", "Early month", "edit delete"),
        )

        applyDateFilterPreset(page, "June 2099", "Previous month")
        assertDateFilterLabel(page, "May 2099")
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$15.00", "May 15", "Main", "Previous month", "edit delete"),
        )

        applyDateFilterPreset(page, "May 2099", "Next month")
        assertDateFilterLabel(page, "July 2099")
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Planned expenses", "A$70.00", "", "", "", "view"),
        )

        applyDateFilterPreset(page, "July 2099", "This year")
        assertDateFilterLabel(page, "This year")
        assertDateRangeArrowsDisabled(page)
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Planned expenses", "A$70.00", "", "", "", "view"),
            ExpenseRow("Groceries", "A$14.00", "Today", "Main", "Today", "edit delete"),
            ExpenseRow("Groceries", "A$3.00", "Jun 3", "Main", "Early month", "edit delete"),
            ExpenseRow("Groceries", "A$15.00", "May 15", "Main", "Previous month", "edit delete"),
            ExpenseRow("Groceries", "A$43.00", "Apr 30", "Main", "April end", "edit delete"),
            ExpenseRow("Groceries", "A$19.00", "Apr 19", "Main", "April partial", "edit delete"),
            ExpenseRow("Rent", "A$30.30", "Mar 3", "Main", "March partial", "edit delete"),
            ExpenseRow("Rent", "A$30.10", "Mar 1", "Main", "March start", "edit delete"),
        )

        applyDateFilterPreset(page, "This year", "All time")
        assertDateFilterLabel(page, "All time")
        assertDateRangeArrowsDisabled(page)
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Planned expenses", "A$70.00", "", "", "", "view"),
            ExpenseRow("Groceries", "A$14.00", "Today", "Main", "Today", "edit delete"),
            ExpenseRow("Groceries", "A$3.00", "Jun 3", "Main", "Early month", "edit delete"),
            ExpenseRow("Groceries", "A$15.00", "May 15", "Main", "Previous month", "edit delete"),
            ExpenseRow("Groceries", "A$43.00", "Apr 30", "Main", "April end", "edit delete"),
            ExpenseRow("Groceries", "A$19.00", "Apr 19", "Main", "April partial", "edit delete"),
            ExpenseRow("Rent", "A$30.30", "Mar 3", "Main", "March partial", "edit delete"),
            ExpenseRow("Rent", "A$30.10", "Mar 1", "Main", "March start", "edit delete"),
            ExpenseRow("Rent", "A$31.00", "Dec 31", "Main", "Previous year", "edit delete"),
        )

        applyCustomVisibleDateRange(page, "All time", startDay = "1", startIndex = 0, endDay = "31", endIndex = 0)
        assertDateFilterLabel(page, "June 2099 - July 2099")
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Planned expenses", "A$70.00", "", "", "", "view"),
            ExpenseRow("Groceries", "A$14.00", "Today", "Main", "Today", "edit delete"),
            ExpenseRow("Groceries", "A$3.00", "Jun 3", "Main", "Early month", "edit delete"),
        )

        applyCustomVisibleDateRange(page, "June 2099 - July 2099", startDay = "3", startIndex = 0, endDay = "19", endIndex = 1)
        assertDateFilterLabel(page, "3 Jun 2099 - 19 Jul 2099")
        assertDateRangeArrowsDisabled(page)
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Planned expenses", "A$70.00", "", "", "", "view"),
            ExpenseRow("Groceries", "A$14.00", "Today", "Main", "Today", "edit delete"),
            ExpenseRow("Groceries", "A$3.00", "Jun 3", "Main", "Early month", "edit delete"),
        )

        page.reload()
        assertDateFilterLabel(page, "June 2099")
    }

    @Test
    fun filtersExpensesByCategoryAccountAndNotes(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val travel = saveAccount(alice, "Travel", "AUD", isDefault = false)
        val groceries = saveCategory(alice, "Groceries")
        val rent = saveCategory(alice, "Rent")
        saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE, 1200, "Coffee beans wholesale")
        saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE, 1300, "Coffee filters")
        saveExpense(alice, travel, groceries, TestTimeProvider.DEFAULT_DATE, 1400, "Coffee beans travel")
        saveExpense(alice, main, rent, TestTimeProvider.DEFAULT_DATE, 1500, "Coffee beans rent")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/expenses")

        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Rent", "A$15.00", "Today", "Main", "Coffee beans rent", "edit delete"),
            ExpenseRow("Groceries", "A$14.00", "Today", "Travel", "Coffee beans travel", "edit delete"),
            ExpenseRow("Groceries", "A$13.00", "Today", "Main", "Coffee filters", "edit delete"),
            ExpenseRow("Groceries", "A$12.00", "Today", "Main", "Coffee beans wholesale", "edit delete"),
        )

        openMoreFilters(page)
        selectMoreFilterOption(page, "Category", "Groceries")
        selectMoreFilterOption(page, "Account", "Main")
        page.getByLabel("Notes").fill("coffee beans")

        assertThat(page.getByLabel("Selected category").getByText("Groceries")).isVisible()
        assertThat(page.getByLabel("Selected account").getByText("Main")).isVisible()
        assertThat(page.locator(".transaction-filter-count-badge")).hasText("3")
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$12.00", "Today", "Main", "Coffee beans wholesale", "edit delete"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Clear all")).click()
        assertThat(page.locator(".transaction-filter-count-badge")).not().isVisible()
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Rent", "A$15.00", "Today", "Main", "Coffee beans rent", "edit delete"),
            ExpenseRow("Groceries", "A$14.00", "Today", "Travel", "Coffee beans travel", "edit delete"),
            ExpenseRow("Groceries", "A$13.00", "Today", "Main", "Coffee filters", "edit delete"),
            ExpenseRow("Groceries", "A$12.00", "Today", "Main", "Coffee beans wholesale", "edit delete"),
        )
    }

    @Test
    fun createsRecurringExpensesForEveryScheduleOptionFromExpenseForm(page: Page) {
        val alice = saveUser("alice")
        saveAccount(alice, "Main", "AUD", isDefault = true)
        saveCategory(alice, "Rent")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        listOf(
            ScheduleOptionExpectation("Daily", 1, RecurrenceInterval.DAY, TestTimeProvider.DEFAULT_DATE.plusDays(1)),
            ScheduleOptionExpectation("Weekly", 1, RecurrenceInterval.WEEK, TestTimeProvider.DEFAULT_DATE.plusWeeks(1)),
            ScheduleOptionExpectation("Biweekly", 2, RecurrenceInterval.WEEK, TestTimeProvider.DEFAULT_DATE.plusWeeks(2)),
            ScheduleOptionExpectation("Monthly", 1, RecurrenceInterval.MONTH, TestTimeProvider.DEFAULT_DATE.plusMonths(1)),
        ).forEach { expectation ->
            val notes = "${expectation.label} membership"
            page.navigate(server.url.toString() + "/expenses/create")

            assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()
            selectCategoryOption(page, "Category", "Rent")
            page.locator("input[name='amount']").fill("25")
            page.getByLabel("Notes").fill(notes)
            page.getByLabel("Recurring expense").press("Space")
            selectOption(page, "Repeat", expectation.label)
            page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create expense")).click()

            page.waitForURL("**/expenses")
            assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
            val rule = recurringExpenseRuleRepository.findAll().single { it.notes == notes }
            rule.recurrenceFrequency.shouldBe(expectation.frequency)
            rule.recurrenceInterval.shouldBe(expectation.interval)
            rule.endDate.shouldBe(null)
            val generatedExpenses = expenseRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!)
            generatedExpenses.take(2).map { it.recurringInstanceDate }.shouldContainExactly(
                TestTimeProvider.DEFAULT_DATE,
                expectation.secondOccurrenceDate,
            )
            generatedExpenses.first().notes.shouldBe(notes)
        }

        page.navigate(server.url.toString() + "/expenses/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()
        selectCategoryOption(page, "Category", "Rent")
        page.locator("input[name='amount']").fill("25")
        page.getByLabel("Notes").fill("Custom membership")
        page.getByLabel("Recurring expense").press("Space")
        selectOption(page, "Repeat", "Custom")
        assertThat(page.getByLabel("Repeat every")).isVisible()
        assertThat(page.getByLabel("Cadence")).isVisible()
        assertThat(page.getByLabel("End after repetitions")).isVisible()
        selectOption(page, "End after repetitions", "3")
        assertThat(page.getByText("Jun 28, 2099", Page.GetByTextOptions().setExact(true))).isVisible()
        selectOption(page, "Repeat every", "3")
        assertThat(page.getByText("Jul 26, 2099", Page.GetByTextOptions().setExact(true))).isVisible()
        selectOption(page, "Cadence", "Days")
        assertThat(page.getByText("Jun 20, 2099", Page.GetByTextOptions().setExact(true))).isVisible()
        selectOption(page, "Cadence", "Weeks")
        assertThat(page.getByText("Jul 26, 2099", Page.GetByTextOptions().setExact(true))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create expense")).click()

        page.waitForURL("**/expenses")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        val storedRecurrenceConfiguration = page.evaluate(
            "window.localStorage.getItem('renalo.expenses.lastRecurrenceConfiguration')",
        ) as String
        storedRecurrenceConfiguration.shouldBe(
            """{"schedule":"CUSTOM","customFrequency":3,"customInterval":"WEEK","repetitions":"3"}""",
        )
        val customRule = recurringExpenseRuleRepository.findAll().single { it.notes == "Custom membership" }
        customRule.recurrenceFrequency.shouldBe(3)
        customRule.recurrenceInterval.shouldBe(RecurrenceInterval.WEEK)
        customRule.endDate.shouldBe(TestTimeProvider.DEFAULT_DATE.plusWeeks(6))
        val customGeneratedExpenses = expenseRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(customRule.id!!)
        customGeneratedExpenses.take(2).map { it.recurringInstanceDate }.shouldContainExactly(
            TestTimeProvider.DEFAULT_DATE,
            TestTimeProvider.DEFAULT_DATE.plusWeeks(3),
        )
        customGeneratedExpenses.first().notes.shouldBe("Custom membership")

        page.navigate(server.url.toString() + "/expenses/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()
        page.getByLabel("Recurring expense").isChecked().shouldBe(false)
        page.getByLabel("Recurring expense").press("Space")

        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Custom Repeat"))).isVisible()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("3 Repeat every"))).isVisible()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Weeks Cadence"))).isVisible()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("3 End after repetitions"))).isVisible()
        assertThat(page.getByText("Jul 26, 2099", Page.GetByTextOptions().setExact(true))).isVisible()
    }

    @Test
    fun togglesRecurringExpenseCheckboxOnMobile(page: Page) {
        val alice = saveUser("alice")
        saveAccount(alice, "Main", "AUD", isDefault = true)
        saveCategory(alice, "Rent")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.setViewportSize(390, 844)

        page.navigate(server.url.toString() + "/expenses/create")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()
        page.locator(".transaction-recurring-checkbox").click()
        assertThat(page.getByLabel("Recurring expense")).isChecked()
        assertThat(page.getByLabel("Repeat")).isVisible()
        page.locator(".transaction-recurring-checkbox").click()
        assertThat(page.getByLabel("Recurring expense")).not().isChecked()
        assertThat(page.getByLabel("Repeat")).not().isVisible()
    }

    @Test
    fun editsRecurringExpensesWithSelectedScopeFromExpenseForm(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val rent = saveCategory(alice, "Rent")
        val bills = saveCategory(alice, "Bills")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        val occurrenceOnlyRule = saveRecurringRule(alice, main, rent, notes = "Occurrence only")
        val occurrenceOnlySelected = saveExpense(
            alice,
            main,
            rent,
            TestTimeProvider.DEFAULT_DATE,
            2500,
            "Occurrence only",
            occurrenceOnlyRule,
        )
        val occurrenceOnlyFollowing = saveExpense(
            alice,
            main,
            rent,
            TestTimeProvider.DEFAULT_DATE.plusWeeks(1),
            2500,
            "Occurrence only",
            occurrenceOnlyRule,
        )

        page.navigate(server.url.toString() + "/expenses/${occurrenceOnlySelected.id}")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Edit expense"))).isVisible()
        assertThat(page.getByText("Repeats weekly until 21 Jun 2099")).isVisible()
        assertThat(page.getByText("The first occurrence in this series is on 14 Jun 2099.")).isVisible()
        assertThat(page.getByText("Date and schedule cannot be edited.")).isVisible()
        assertThat(page.locator(".transaction-date-field").getByRole(AriaRole.BUTTON)).isDisabled()
        assertThat(page.getByLabel("Recurring expense")).not().isVisible()
        assertThat(page.getByLabel("Repeat")).not().isVisible()
        selectCategoryOption(page, "Category", "Bills")
        page.locator("input[name='amount']").fill("31")
        page.getByLabel("Notes").fill("Edited one")
        selectOption(page, "Edit scope", "This occurrence only")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save expense")).click()

        page.waitForURL("**/expenses")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        val editedOccurrence = expenseRepository.findById(occurrenceOnlySelected.id!!).get()
        editedOccurrence.categoryId.shouldBe(bills.id)
        editedOccurrence.amountMinor.shouldBe(3100)
        editedOccurrence.notes.shouldBe("Edited one")
        editedOccurrence.recurringLocked.shouldBe(true)
        expenseRepository.findById(occurrenceOnlyFollowing.id!!).get().amountMinor.shouldBe(2500)

        val followingRule = saveRecurringRule(alice, main, rent, notes = "Following")
        saveExpense(alice, main, rent, TestTimeProvider.DEFAULT_DATE, 2500, "Following", followingRule)
        val followingSelected = saveExpense(
            alice,
            main,
            rent,
            TestTimeProvider.DEFAULT_DATE.plusWeeks(1),
            2500,
            "Following",
            followingRule,
        )
        saveExpense(alice, main, rent, TestTimeProvider.DEFAULT_DATE.plusWeeks(2), 2500, "Following", followingRule)

        page.navigate(server.url.toString() + "/expenses/${followingSelected.id}")
        selectOption(page, "Edit scope", "This and all following occurrences")
        page.locator("input[name='amount']").fill("41")
        page.getByLabel("Notes").fill("Edited following")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save expense")).click()

        page.waitForURL("**/expenses")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        recurringExpenseRuleRepository.findById(followingRule.id!!).get().endDate
            .shouldBe(TestTimeProvider.DEFAULT_DATE.plusDays(6))
        val newFollowingRule = recurringExpenseRuleRepository.findAll().single {
            it.id != followingRule.id && it.notes == "Edited following"
        }
        newFollowingRule.startDate.shouldBe(TestTimeProvider.DEFAULT_DATE.plusWeeks(1))
        newFollowingRule.amountMinor.shouldBe(4100)
        expenseRepository.findById(followingSelected.id!!).get().recurringRuleId.shouldBe(newFollowingRule.id)

        val allRule = saveRecurringRule(alice, main, rent, notes = "All")
        val allFirst = saveExpense(alice, main, rent, TestTimeProvider.DEFAULT_DATE, 2500, "All", allRule)
        val allSecond = saveExpense(alice, main, rent, TestTimeProvider.DEFAULT_DATE.plusWeeks(1), 2500, "All", allRule)

        page.navigate(server.url.toString() + "/expenses/${allFirst.id}")
        selectOption(page, "Edit scope", "All occurrences")
        page.locator("input[name='amount']").fill("51")
        page.getByLabel("Notes").fill("Edited all")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save expense")).click()

        page.waitForURL("**/expenses")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        recurringExpenseRuleRepository.findById(allRule.id!!).get().amountMinor.shouldBe(5100)
        expenseRepository.findById(allFirst.id!!).get().notes.shouldBe("Edited all")
        expenseRepository.findById(allSecond.id!!).get().amountMinor.shouldBe(5100)
    }

    @Test
    fun deletesRecurringExpensesWithSelectedScopeFromExpensesPage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val rent = saveCategory(alice, "Rent")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        val occurrenceOnlyRule = saveRecurringRule(alice, main, rent, notes = "Occurrence only")
        val occurrenceOnlySelected = saveExpense(
            alice,
            main,
            rent,
            TestTimeProvider.DEFAULT_DATE,
            2500,
            "Occurrence only selected",
            occurrenceOnlyRule,
        )
        val occurrenceOnlyFollowing = saveExpense(
            alice,
            main,
            rent,
            TestTimeProvider.DEFAULT_DATE.plusWeeks(1),
            2500,
            "Occurrence only following",
            occurrenceOnlyRule,
        )

        page.navigate(server.url.toString() + "/expenses")
        page.locator("[data-testid='expense-row-${occurrenceOnlySelected.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Delete Rent expense"))
            .click()

        assertThat(page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Delete Rent expense?"))).isVisible()
        assertThat(
            page.getByText(
                "This expense is part of the repeated series starting 14 Jun 2099 and ending 21 Jun 2099.",
            ),
        ).isVisible()
        assertThat(page.getByLabel("Delete scope")).isVisible()
        assertThat(page.getByText("This occurrence only")).isVisible()
        assertThat(page.getByText("This and all following occurrences")).isVisible()
        assertThat(page.getByText("All occurrences")).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Delete expense")).click()

        page.shouldEventuallyContainExpenseRows(
            ExpenseRow(
                "Planned expenses",
                "A$25.00",
                "",
                "",
                "",
                "view",
            ),
        )
        expenseRepository.findById(occurrenceOnlySelected.id!!).isPresent.shouldBe(false)
        expenseRepository.findById(occurrenceOnlyFollowing.id!!).isPresent.shouldBe(true)

        val followingRule = saveRecurringRule(alice, main, rent, notes = "Following")
        val followingBefore = saveExpense(
            alice,
            main,
            rent,
            TestTimeProvider.DEFAULT_DATE,
            2500,
            "Following before",
            followingRule,
        )
        val followingSelected = saveExpense(
            alice,
            main,
            rent,
            TestTimeProvider.DEFAULT_DATE.plusWeeks(1),
            2500,
            "Following selected",
            followingRule,
        )
        val followingLater = saveExpense(
            alice,
            main,
            rent,
            TestTimeProvider.DEFAULT_DATE.plusWeeks(2),
            2500,
            "Following later",
            followingRule,
        )

        page.navigate(server.url.toString() + "/expenses")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("View all planned expenses")).click()
        page.locator("[data-testid='expense-row-${followingSelected.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Delete Rent expense"))
            .click()
        page.getByText("This and all following occurrences").click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Delete expense")).click()

        page.shouldEventuallyContainExpenseRows(
            ExpenseRow(
                "Rent",
                "A$25.00",
                "Today Repeats weekly until 20 Jun 2099",
                "Main",
                "Following before",
                "edit delete",
            ),
            ExpenseRow(
                "Rent",
                "A$25.00",
                "Jun 21 Repeats weekly until 21 Jun 2099",
                "Main",
                "Occurrence only following",
                "edit delete",
            ),
        )
        expenseRepository.findById(followingBefore.id!!).isPresent.shouldBe(true)
        expenseRepository.findById(followingSelected.id!!).isPresent.shouldBe(false)
        expenseRepository.findById(followingLater.id!!).isPresent.shouldBe(false)

        val allRule = saveRecurringRule(alice, main, rent, notes = "All")
        val allFirst = saveExpense(alice, main, rent, TestTimeProvider.DEFAULT_DATE, 2500, "All first", allRule)
        val allSecond = saveExpense(alice, main, rent, TestTimeProvider.DEFAULT_DATE.plusWeeks(1), 2500, "All second", allRule)

        page.navigate(server.url.toString() + "/expenses")
        page.locator("[data-testid='expense-row-${allFirst.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Delete Rent expense"))
            .click()
        page.getByText("All occurrences").click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Delete expense")).click()

        page.shouldEventuallyContainExpenseRows(
            ExpenseRow(
                "Rent",
                "A$25.00",
                "Today Repeats weekly until 20 Jun 2099",
                "Main",
                "Following before",
                "edit delete",
            ),
            ExpenseRow(
                "Planned expenses",
                "A$25.00",
                "",
                "",
                "",
                "view",
            ),
        )
        expenseRepository.findById(allFirst.id!!).isPresent.shouldBe(false)
        expenseRepository.findById(allSecond.id!!).isPresent.shouldBe(false)
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

    private fun saveAccount(
        user: User,
        name: String,
        currency: String,
        isDefault: Boolean,
        archived: Boolean = false,
    ): TrackingAccount =
        trackingAccountRepository.save(
            TrackingAccount(
                userId = user.id!!,
                name = name,
                currency = currency,
                initialBalanceMinor = 0,
                isDefault = isDefault,
                archived = archived,
            ),
        )

    private fun saveCategory(user: User, name: String, archived: Boolean = false): ExpenseCategory = expenseCategoryRepository.save(
        ExpenseCategory(
            userId = user.id!!,
            name = name,
            archived = archived,
        ),
    )

    private fun saveExpense(
        user: User,
        account: TrackingAccount,
        category: ExpenseCategory,
        date: LocalDate,
        amountMinor: Long,
        notes: String?,
        recurringRule: RecurringTransactionRule? = null,
        recurringLocked: Boolean = false,
    ): Transaction = expenseRepository.save(
        Transaction(
            userId = user.id!!,
                type = TransactionType.EXPENSE,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            date = date,
            amountMinor = amountMinor,
            notes = notes,
            recurringRuleId = recurringRule?.id,
            recurringInstanceDate = recurringRule?.let { date },
            recurringLocked = recurringLocked,
        ),
    )

    private fun saveRecurringRule(
        user: User,
        account: TrackingAccount,
        category: ExpenseCategory,
        notes: String = "Subscription",
        startDate: LocalDate = TestTimeProvider.DEFAULT_DATE,
        endDate: LocalDate = TestTimeProvider.DEFAULT_DATE.plusWeeks(1),
    ): RecurringTransactionRule =
        recurringExpenseRuleRepository.save(
            RecurringTransactionRule(
                userId = user.id!!,
                transactionType = TransactionType.EXPENSE,
                trackingAccountId = account.id!!,
                categoryId = category.id!!,
                startDate = startDate,
                endDate = endDate,
                recurrenceFrequency = 1,
                recurrenceInterval = RecurrenceInterval.WEEK,
                generatedUntil = startDate,
                amountMinor = 2500,
                notes = notes,
            ),
        )

    @Test
    fun persistsSelectedExpenseAccountBetweenNavigations(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val travel = saveAccount(alice, "Travel", "EUR", isDefault = false)
        saveCategory(alice, "Groceries")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/expenses/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()
        assertThat(page.getByLabel("Account").last()).containsText("Main")

        page.getByLabel("Account").last().click()
        dropdownOption(page, "Travel").click()
        assertThat(page.getByLabel("Account").last()).containsText("Travel")

        page.navigate(server.url.toString() + "/expenses")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()

        page.navigate(server.url.toString() + "/expenses/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()
        assertThat(page.getByLabel("Account").last()).containsText("Travel")
    }

    @Test
    fun showsCategoriesInUsageOrderOnExpenseForm(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val groceries = saveCategory(alice, "Groceries")
        val rent = saveCategory(alice, "Rent")
        val utilities = saveCategory(alice, "Utilities")
        saveExpense(alice, main, rent, TestTimeProvider.DEFAULT_DATE, 1000, "Rent")
        saveExpense(alice, main, utilities, TestTimeProvider.DEFAULT_DATE.minusDays(5), 2000, "Utilities")
        saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE.minusDays(10), 3000, "Groceries")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/expenses/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()

        page.getByLabel("Category").click()
        val optionOrder = dropdownOptions(page).evaluateAll(
            "options => options.map(o => o.textContent.trim())",
        ) as List<String>
        optionOrder.shouldBe(listOf("Rent", "Utilities", "Groceries"))
        page.keyboard().press("Escape")
    }

    @Test
    fun keepsCategoryEmptyOnCreateForm(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        saveCategory(alice, "Groceries")
        saveCategory(alice, "Rent")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/expenses/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()
        assertThat(page.getByLabel("Category")).containsText("Choose a category")
    }

    @Test
    fun searchesCategoriesOnExpenseForm(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        saveCategory(alice, "Groceries")
        saveCategory(alice, "Rent")
        saveCategory(alice, "Utilities")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/expenses/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()

        page.getByLabel("Category").click()
        page.getByLabel("Search category").fill("Rent")
        assertThat(dropdownOption(page, "Rent")).isVisible()
        assertThat(dropdownOption(page, "Groceries")).not().isVisible()
        assertThat(dropdownOption(page, "Utilities")).not().isVisible()
    }

    private fun selectOption(page: Page, label: String, option: String) {
        page.getByLabel(label).click()
        dropdownOption(page, option).click()
    }

    private fun selectCategoryOption(page: Page, label: String, option: String) {
        page.getByLabel(label).click()
        page.getByLabel("Search ${label.lowercase()}").fill(option)
        dropdownOption(page, option).click()
    }

    private fun dropdownOptions(page: Page): Locator =
        page.locator("[role='menuitem'], [role='menuitemradio'], [role='menuitemcheckbox']")

    private fun dropdownOption(page: Page, option: String): Locator =
        dropdownOptions(page).filter(Locator.FilterOptions().setHasText(option))

    private fun assertRequiredLabel(page: Page, label: String) {
        assertThat(
            page.locator("label")
                .filter(Locator.FilterOptions().setHasText(label))
                .locator("span")
                .filter(Locator.FilterOptions().setHasText("*")),
        ).isVisible()
    }

    private fun assertDateFilterLabel(page: Page, label: String) {
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(label).setExact(true))).isVisible()
    }

    private fun assertDateRangeArrowsDisabled(page: Page) {
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Previous date range"))).isDisabled()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Next date range"))).isDisabled()
    }

    private fun applyDateFilterPreset(page: Page, currentLabel: String, preset: String) {
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(currentLabel).setExact(true)).click()
        val dialog = page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Date range filter"))
        assertThat(dialog).isVisible()
        dialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(preset).setExact(true)).click()
        dialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Apply")).click()
    }

    private fun applyCustomVisibleDateRange(
        page: Page,
        currentLabel: String,
        startDay: String,
        startIndex: Int,
        endDay: String,
        endIndex: Int,
    ) {
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(currentLabel).setExact(true)).click()
        val dialog = page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Date range filter"))
        assertThat(dialog).isVisible()
        calendarDay(dialog, startDay).nth(startIndex).click()
        calendarDay(dialog, endDay).nth(endIndex).click()
        dialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Apply")).click()
    }

    private fun openMoreFilters(page: Page) {
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("More filters")).click()
        assertThat(page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("More filters"))).isVisible()
    }

    private fun selectMoreFilterOption(page: Page, label: String, option: String) {
        page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("More filters"))
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(label).setExact(true))
            .click()
        dropdownOption(page, option).click()
        page.keyboard().press("Escape")
    }

    private fun calendarDay(dialog: Locator, day: String): Locator = dialog.locator("[role='gridcell']:not([aria-disabled='true'])")
        .filter(Locator.FilterOptions().setHasText(Pattern.compile("^$day$")))

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

    private data class ScheduleOptionExpectation(
        val label: String,
        val frequency: Int,
        val interval: RecurrenceInterval,
        val secondOccurrenceDate: LocalDate,
    )
}
