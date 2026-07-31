package io.orangebuffalo.renalo

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestAuthTokens
import io.orangebuffalo.renalo.test.TestTimeProvider
import io.orangebuffalo.renalo.test.shouldEventually
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.FundsTransfer
import io.orangebuffalo.renalo.tracking.FundsTransferRepository
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class DashboardPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

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
    fun showsAccountBalanceAndMoneyFlowCards(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", 10_000, isDefault = true)
        val savings = saveAccount(alice, "Savings", 50_000)
        saveAccount(alice, "Cash", 12_300)
        val groceries = saveExpenseCategory(alice, "Groceries")
        val salary = saveIncomeCategory(alice, "Salary")
        saveTransaction(alice, main, salary, TransactionType.INCOME, TestTimeProvider.DEFAULT_DATE, 20_000)
        saveTransaction(alice, main, groceries, TransactionType.EXPENSE, TestTimeProvider.DEFAULT_DATE, 4_000)
        saveTransfer(alice, main, savings, TestTimeProvider.DEFAULT_DATE, 7_000, 7_000)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/tracking")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
        page.shouldEventuallyContainDashboardCards(
            DashboardCardText("MainTotal balanceA$190.00Inflow JuneA$200.00Outflow JuneA$110.00"),
            DashboardCardText("SavingsTotal balanceA$570.00Inflow JuneA$70.00Outflow JuneA$0.00"),
            DashboardCardText("CashTotal balanceA$123.00Inflow JuneA$0.00Outflow JuneA$0.00"),
        )
        page.locator("[data-testid='dashboard-account-card']").first().scrollIntoViewIfNeeded()
    }

    @Test
    fun opensQuickAddMenuFromDashboard(page: Page) {
        saveUser("alice")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/tracking")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record new").setExact(true)).click()
        assertThat(page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Expense"))).isVisible()
        assertThat(page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Income"))).isVisible()
        page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Transfer")).click()

        assertThat(page).hasURL(Pattern.compile(".*/transfers/create$"))
    }

    @Test
    fun showsTrendChartsAndRestoresSemanticDateRange(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", 0, isDefault = true)
        val groceries = saveExpenseCategory(alice, "Groceries")
        val salary = saveIncomeCategory(alice, "Salary")
        saveTransaction(alice, main, groceries, TransactionType.EXPENSE, LocalDate.of(2099, 1, 4), 2_000)
        saveTransaction(alice, main, groceries, TransactionType.EXPENSE, LocalDate.of(2099, 6, 8), 4_000)
        saveTransaction(alice, main, groceries, TransactionType.EXPENSE, TestTimeProvider.DEFAULT_DATE.plusDays(1), 8_000)
        saveTransaction(alice, main, salary, TransactionType.INCOME, LocalDate.of(2099, 1, 7), 9_000)
        saveTransaction(alice, main, salary, TransactionType.INCOME, LocalDate.of(2099, 6, 10), 12_000)
        saveTransaction(alice, main, salary, TransactionType.INCOME, TestTimeProvider.DEFAULT_DATE.plusDays(1), 16_000)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/tracking")

        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Next date range"))).isDisabled()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Last 12 months").setExact(true)).click()
        val dateDialog = page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Date range filter"))
        assertThat(dateDialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Last 12 months"))).isVisible()
        assertThat(dateDialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Next month"))).hasCount(0)
        assertThat(dateDialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Last 2 years"))).isVisible()
        assertThat(dateDialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Last 3 years"))).isVisible()
        assertThat(dateDialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Last 5 years"))).isVisible()
        dateDialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("This month").setExact(true)).click()
        assertThat(
            dateDialog.locator("[role='gridcell'][aria-disabled='true']")
                .filter(Locator.FilterOptions().setHasText(Pattern.compile("^15$")))
                .first(),
        ).isVisible()
        dateDialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("This year").setExact(true)).click()
        dateDialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Apply").setExact(true)).click()

        val charts = page.locator("[data-testid='transaction-time-series-chart']")
        assertThat(charts).hasCount(3)
        assertThat(charts.nth(0).getByRole(AriaRole.HEADING, Locator.GetByRoleOptions().setName("Expenses"))).isVisible()
        assertThat(charts.nth(1).getByRole(AriaRole.HEADING, Locator.GetByRoleOptions().setName("Income"))).isVisible()
        assertThat(charts.nth(2).getByRole(AriaRole.HEADING, Locator.GetByRoleOptions().setName("Net Worth"))).isVisible()
        assertThat(charts.nth(0).getByText("Weekly totals (AUD)")).isVisible()
        assertThat(charts.nth(1).getByText("Weekly totals (AUD)")).isVisible()
        assertThat(page.getByText("Dashed line: trend")).hasCount(0)
        page.shouldEventuallyContainChartPoints(
            charts.nth(0),
            ChartPoint("2098-12-29", "AUD", 2_000),
            ChartPoint("2099-06-08", "AUD", 4_000),
        )
        page.shouldEventuallyContainChartPoints(
            charts.nth(1),
            ChartPoint("2099-01-05", "AUD", 9_000),
            ChartPoint("2099-06-08", "AUD", 12_000),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Maximize Expenses chart")).click()
        val maximizedChart = page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Expenses chart"))
        assertThat(maximizedChart).isVisible()
        page.shouldEventuallyContainChartPoints(
            maximizedChart.locator("[data-testid='transaction-time-series-chart']"),
            ChartPoint("2098-12-29", "AUD", 2_000),
            ChartPoint("2099-06-08", "AUD", 4_000),
        )
        maximizedChart.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Close Expenses chart")).click()
        assertThat(maximizedChart).not().isVisible()

        page.evaluate("window.localStorage.getItem('renalo.dashboard.dateFilter')")
            .shouldBe("""{"preset":"THIS_YEAR"}""")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("This year").setExact(true)).click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("All time").setExact(true)).click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Apply").setExact(true)).click()
        page.shouldEventuallyContainChartPoints(
            charts.nth(0),
            ChartPoint("2098-12-29", "AUD", 2_000),
            ChartPoint("2099-06-08", "AUD", 4_000),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("All time").setExact(true)).click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Last 2 years").setExact(true)).click()
        assertThat(page.getByText("15 Jun 2097 - 14 Jun 2099")).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Apply").setExact(true)).click()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Last 2 years").setExact(true))).isVisible()
        page.evaluate("window.localStorage.getItem('renalo.dashboard.dateFilter')")
            .shouldBe("""{"preset":"LAST_2_YEARS"}""")

        page.clock().setFixedTime(
            Date.from(TestTimeProvider.DEFAULT_TIME.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()),
        )
        page.reload()

        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Last 2 years").setExact(true))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Last 2 years").setExact(true)).click()
        assertThat(page.getByText("15 Jul 2097 - 14 Jul 2099")).isVisible()
    }

    @Test
    fun filtersExpensesByCategoryAndPersistsCategoryVisibility(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", 0, isDefault = true)
        val groceries = saveExpenseCategory(alice, "Groceries")
        val rent = saveExpenseCategory(alice, "Rent")
        saveTransaction(alice, main, groceries, TransactionType.EXPENSE, TestTimeProvider.DEFAULT_DATE, 4_000)
        saveTransaction(alice, main, rent, TransactionType.EXPENSE, LocalDate.of(2099, 1, 4), 2_000)
        saveTransaction(alice, main, rent, TransactionType.EXPENSE, LocalDate.of(2098, 1, 4), 8_000)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/tracking")

        val chart = page.locator("[data-testid='expense-category-chart']")
        page.shouldEventuallyContainCategoryRows(
            chart,
            CategoryChartRow(groceries.id!!, "Groceries", "AUD", 4_000, true),
            CategoryChartRow(rent.id!!, "Rent", "AUD", 2_000, true),
        )
        chart.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Maximize Expenses by category chart"))
            .click()
        val maximizedChart = page.getByRole(
            AriaRole.DIALOG,
            Page.GetByRoleOptions().setName("Expenses by category chart"),
        )
        assertThat(maximizedChart).isVisible()
        page.shouldEventuallyContainCategoryRows(
            maximizedChart.locator("[data-testid='expense-category-chart']"),
            CategoryChartRow(groceries.id!!, "Groceries", "AUD", 4_000, true),
            CategoryChartRow(rent.id!!, "Rent", "AUD", 2_000, true),
        )
        maximizedChart.getByRole(
            AriaRole.BUTTON,
            Locator.GetByRoleOptions().setName("Close Expenses by category chart"),
        ).click()
        assertThat(maximizedChart).not().isVisible()
        assertThat(chart.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Hide Groceries"))).containsText("67%")
        val rentToggle = chart.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Hide Rent"))
        assertThat(rentToggle).containsText("33%")
        val groceriesSlice = chart.locator(".recharts-pie-sector path").first()
        groceriesSlice.dispatchEvent("pointerover", mapOf("pointerType" to "touch"))
        assertThat(chart.locator("[data-testid='transaction-category-chart-row'][data-category-id='${groceries.id}']"))
            .hasAttribute("data-highlighted", "false")
        groceriesSlice.dispatchEvent("pointerover", mapOf("pointerType" to "mouse"))
        assertThat(chart.locator("[data-testid='transaction-category-chart-row'][data-category-id='${groceries.id}']"))
            .hasAttribute("data-highlighted", "true")
        assertThat(chart.locator("[data-testid='transaction-category-chart-row'][data-category-id='${rent.id}']"))
            .hasAttribute("data-highlighted", "false")
        groceriesSlice.dispatchEvent("pointerout", mapOf("pointerType" to "mouse"))
        assertThat(chart.locator("[data-testid='transaction-category-chart-row'][data-category-id='${groceries.id}']"))
            .hasAttribute("data-highlighted", "false")
        rentToggle.click()
        page.shouldEventuallyContainCategoryRows(
            chart,
            CategoryChartRow(groceries.id!!, "Groceries", "AUD", 4_000, true),
            CategoryChartRow(rent.id!!, "Rent", "AUD", 2_000, false),
        )
        assertThat(chart.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Hide Groceries"))).containsText("100%")
        assertThat(chart.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Show Rent"))).hasText("Rent")
        page.evaluate("window.localStorage.getItem('renalo.dashboard.expenseCategoryVisibility')")
            .shouldBe("""{"hiddenCategoryIds":[${rent.id}]}""")

        page.reload()

        page.shouldEventuallyContainCategoryRows(
            chart,
            CategoryChartRow(groceries.id!!, "Groceries", "AUD", 4_000, true),
            CategoryChartRow(rent.id!!, "Rent", "AUD", 2_000, false),
        )
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Last 12 months").setExact(true)).click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("This month").setExact(true)).click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Apply").setExact(true)).click()
        page.shouldEventuallyContainCategoryRows(
            chart,
            CategoryChartRow(groceries.id!!, "Groceries", "AUD", 4_000, true),
        )
    }

    @Test
    fun filtersIncomeByCategoryAndPersistsCategoryVisibility(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", 0, isDefault = true)
        val salary = saveIncomeCategory(alice, "Salary")
        val freelancing = saveIncomeCategory(alice, "Freelancing")
        saveTransaction(alice, main, salary, TransactionType.INCOME, TestTimeProvider.DEFAULT_DATE, 6_000)
        saveTransaction(alice, main, freelancing, TransactionType.INCOME, LocalDate.of(2099, 1, 4), 2_000)
        saveTransaction(alice, main, freelancing, TransactionType.INCOME, LocalDate.of(2098, 1, 4), 8_000)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/tracking")

        val chart = page.locator("[data-testid='income-category-chart']")
        page.shouldEventuallyContainCategoryRows(
            chart,
            CategoryChartRow(salary.id!!, "Salary", "AUD", 6_000, true),
            CategoryChartRow(freelancing.id!!, "Freelancing", "AUD", 2_000, true),
        )
        assertThat(
            chart.getByRole(
                AriaRole.BUTTON,
                Locator.GetByRoleOptions().setName("Maximize Income by category chart"),
            ),
        ).isVisible()
        assertThat(chart.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Hide Salary"))).containsText("75%")
        val freelancingToggle = chart.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Hide Freelancing"))
        assertThat(freelancingToggle).containsText("25%")
        freelancingToggle.click()
        page.shouldEventuallyContainCategoryRows(
            chart,
            CategoryChartRow(salary.id!!, "Salary", "AUD", 6_000, true),
            CategoryChartRow(freelancing.id!!, "Freelancing", "AUD", 2_000, false),
        )
        assertThat(chart.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Hide Salary"))).containsText("100%")
        assertThat(chart.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Show Freelancing")))
            .hasText("Freelancing")
        page.evaluate("window.localStorage.getItem('renalo.dashboard.incomeCategoryVisibility')")
            .shouldBe("""{"hiddenCategoryIds":[${freelancing.id}]}""")
        page.evaluate("window.localStorage.getItem('renalo.dashboard.expenseCategoryVisibility')").shouldBe(null)

        page.reload()

        page.shouldEventuallyContainCategoryRows(
            chart,
            CategoryChartRow(salary.id!!, "Salary", "AUD", 6_000, true),
            CategoryChartRow(freelancing.id!!, "Freelancing", "AUD", 2_000, false),
        )
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Last 12 months").setExact(true)).click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("This month").setExact(true)).click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Apply").setExact(true)).click()
        page.shouldEventuallyContainCategoryRows(
            chart,
            CategoryChartRow(salary.id!!, "Salary", "AUD", 6_000, true),
        )
    }

    @Test
    fun showsTopExpenseCategoriesAndTogglesCategoriesFromOverflow(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", 0, isDefault = true)
        val categories = (1..10).map { index ->
            saveExpenseCategory(alice, "Category $index").also { category ->
                saveTransaction(
                    alice,
                    main,
                    category,
                    TransactionType.EXPENSE,
                    TestTimeProvider.DEFAULT_DATE,
                    11_000L - index * 1_000L,
                )
            }
        }
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/tracking")

        val chart = page.locator("[data-testid='expense-category-chart']")
        page.shouldEventuallyContainCategoryRows(
            chart,
            *categories.mapIndexed { index, category ->
                CategoryChartRow(
                    category.id!!,
                    category.name,
                    "AUD",
                    10_000L - index * 1_000L,
                    true,
                )
            }.toTypedArray(),
        )
        val mainLegend = chart.getByRole(AriaRole.GROUP, Locator.GetByRoleOptions().setName("Expense categories"))
        page.shouldEventually {
            mainLegend.getByRole(AriaRole.BUTTON).all().map { it.getAttribute("aria-label") }
                .shouldContainExactly((1..8).map { "Hide Category $it" })
        }

        chart.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("2 more")).click()
        val overflow = page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("More expense categories"))
        assertThat(overflow).isVisible()
        overflow.getByRole(AriaRole.BUTTON).all().map { it.getAttribute("aria-label") }
            .shouldContainExactly("Hide Category 9", "Hide Category 10")

        overflow.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Hide Category 10")).click()
        assertThat(overflow.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Show Category 10")))
            .hasText("Category 10")
        page.evaluate("window.localStorage.getItem('renalo.dashboard.expenseCategoryVisibility')")
            .shouldBe("""{"hiddenCategoryIds":[${categories.last().id}]}""")
    }

    @Test
    fun showsNetWorthAcrossActiveAndArchivedAccounts(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", 10_000, isDefault = true)
        val archived = saveAccount(alice, "Old savings", 5_000, archived = true)
        val groceries = saveExpenseCategory(alice, "Groceries")
        val salary = saveIncomeCategory(alice, "Salary")
        saveTransaction(alice, main, salary, TransactionType.INCOME, LocalDate.of(2099, 1, 1), 1_000)
        saveTransaction(alice, archived, groceries, TransactionType.EXPENSE, LocalDate.of(2099, 1, 2), 200)
        saveTransfer(alice, main, archived, LocalDate.of(2099, 1, 3), 300, 300)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.addInitScript(
            "window.localStorage.setItem('renalo.dashboard.dateFilter', " +
                "JSON.stringify({ from: '2099-01-01', to: '2099-01-03' }))",
        )

        page.navigate(server.url.toString() + "/tracking")

        val netWorthChart = page.locator("[data-chart-title='Net Worth']")
        assertThat(netWorthChart.getByText("All accounts · Daily balances (AUD)")).isVisible()
        page.shouldEventuallyContainChartPoints(
            netWorthChart,
            ChartPoint("2099-01-01", "AUD", 16_000),
            ChartPoint("2099-01-02", "AUD", 15_800),
            ChartPoint("2099-01-03", "AUD", 15_800),
        )
        assertThat(netWorthChart.locator(".recharts-line path")).isVisible()
        netWorthChart.scrollIntoViewIfNeeded()
        page.waitForTimeout(1_600.0)
    }

    @Test
    fun managesPersistedExpenseChartPresets(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", 0, isDefault = true)
        val groceries = saveExpenseCategory(alice, "Groceries")
        val taxes = saveExpenseCategory(alice, "Taxes")
        saveTransaction(alice, main, groceries, TransactionType.EXPENSE, TestTimeProvider.DEFAULT_DATE, 1_000)
        saveTransaction(alice, main, taxes, TransactionType.EXPENSE, TestTimeProvider.DEFAULT_DATE, 5_000)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        val incomeAnalyticsRequests = AtomicInteger()
        page.route("**/api/tracking/analytics/transactions/INCOME/time-series**") { route ->
            incomeAnalyticsRequests.incrementAndGet()
            route.resume()
        }

        page.navigate(server.url.toString() + "/tracking")

        val expenseChart = page.locator("[data-testid='transaction-time-series-chart']").nth(0)
        page.shouldEventuallyContainChartPoints(
            expenseChart,
            ChartPoint("2099-06-01", "AUD", 6_000),
        )
        assertThat(expenseChart.getByText(Pattern.compile("All expenses.*Monthly totals \\(AUD\\)"))).isVisible()

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Configure expenses chart")).click()
        page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Create preset")).click()
        val createDialog = page.getByRole(
            AriaRole.DIALOG,
            Page.GetByRoleOptions().setName("Create expenses chart preset"),
        )
        assertThat(createDialog).isVisible()
        createDialog.getByLabel("Preset name").fill("Everyday spending")
        createDialog.getByRole(
            AriaRole.BUTTON,
            Locator.GetByRoleOptions().setName(Pattern.compile("Category filter")),
        ).click()
        page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName("Exclude").setExact(true)).click()
        createDialog.getByRole(
            AriaRole.BUTTON,
            Locator.GetByRoleOptions().setName("Categories").setExact(true),
        ).click()
        multiDropdownRow(page, "Taxes").click()
        page.keyboard().press("Escape")
        createDialog.getByText("Taxes", Locator.GetByTextOptions().setExact(true)).count().shouldBe(0)
        createDialog.getByRole(
            AriaRole.BUTTON,
            Locator.GetByRoleOptions().setName(Pattern.compile("Grouping")),
        ).click()
        page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName("Weekly").setExact(true)).click()
        createDialog.getByRole(
            AriaRole.BUTTON,
            Locator.GetByRoleOptions().setName("Create preset").setExact(true),
        ).click()

        assertThat(createDialog).not().isVisible()
        assertThat(expenseChart.getByText(Pattern.compile("Everyday spending.*Weekly totals \\(AUD\\)"))).isVisible()
        page.shouldEventuallyContainChartPoints(
            expenseChart,
            ChartPoint("2099-06-08", "AUD", 1_000),
        )
        incomeAnalyticsRequests.get().shouldBe(1)

        page.reload()

        assertThat(expenseChart.getByText(Pattern.compile("Everyday spending.*Weekly totals \\(AUD\\)"))).isVisible()
        page.shouldEventuallyContainChartPoints(
            expenseChart,
            ChartPoint("2099-06-08", "AUD", 1_000),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Configure expenses chart")).click()
        page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Edit current preset")).click()
        val editDialog = page.getByRole(
            AriaRole.DIALOG,
            Page.GetByRoleOptions().setName("Edit expenses chart preset"),
        )
        editDialog.getByLabel("Preset name").fill("Taxes only")
        editDialog.getByRole(
            AriaRole.BUTTON,
            Locator.GetByRoleOptions().setName(Pattern.compile("Category filter")),
        ).click()
        page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName("Include only").setExact(true)).click()
        editDialog.getByRole(
            AriaRole.BUTTON,
            Locator.GetByRoleOptions().setName("Save changes").setExact(true),
        ).click()

        assertThat(editDialog).not().isVisible()
        assertThat(expenseChart.getByText(Pattern.compile("Taxes only.*Weekly totals \\(AUD\\)"))).isVisible()
        page.shouldEventuallyContainChartPoints(
            expenseChart,
            ChartPoint("2099-06-08", "AUD", 5_000),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Configure expenses chart")).click()
        page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Delete current preset")).click()
        val deleteDialog = page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Delete “Taxes only”?"))
        assertThat(deleteDialog).isVisible()
        deleteDialog.getByRole(
            AriaRole.BUTTON,
            Locator.GetByRoleOptions().setName("Delete preset").setExact(true),
        ).click()

        assertThat(deleteDialog).not().isVisible()
        assertThat(expenseChart.getByText(Pattern.compile("All expenses.*Monthly totals \\(AUD\\)"))).isVisible()
        page.shouldEventuallyContainChartPoints(
            expenseChart,
            ChartPoint("2099-06-01", "AUD", 6_000),
        )
    }

    @Test
    fun centersQuickAddButtonOnMobile(page: Page) {
        saveUser("alice")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.setViewportSize(390, 844)

        page.navigate(server.url.toString() + "/tracking")

        val addButton = page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record new").setExact(true))
        assertThat(addButton).isVisible()

        @Suppress("UNCHECKED_CAST")
        val dimensions = addButton.evaluate(
            """
                button => {
                    const rect = button.getBoundingClientRect();
                    const actionsRect = button.closest('.standard-page-actions').getBoundingClientRect();
                    return [rect.width, rect.left - actionsRect.left, actionsRect.width];
                }
            """.trimIndent(),
        ) as List<Number>
        val width = dimensions[0].toDouble()
        val left = dimensions[1].toDouble()
        val actionsWidth = dimensions[2].toDouble()

        (width >= actionsWidth * 0.78).shouldBe(true)
        (kotlin.math.abs(left - ((actionsWidth - width) / 2)) < 3).shouldBe(true)
        addButton.click()
        assertThat(page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Expense"))).isVisible()
    }

    @Test
    fun opensChartPresetEditorAsFullPageOverlayOnMobile(page: Page) {
        val user = saveUser("alice")
        saveAccount(user, "Everyday", 0, isDefault = true)
        saveExpenseCategory(user, "General")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.setViewportSize(390, 844)

        page.navigate(server.url.toString() + "/tracking")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Configure expenses chart")).click()
        page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Create preset")).click()

        val dialog = page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Create expenses chart preset"))
        assertThat(dialog).isVisible()
        assertThat(dialog.getByLabel("Preset name")).isVisible()

        @Suppress("UNCHECKED_CAST")
        val overlayBounds = page.locator(".dashboard-preset-modal-overlay").evaluate(
            """
                overlay => {
                    const rect = overlay.getBoundingClientRect();
                    return [rect.left, rect.top, rect.width, rect.height];
                }
            """.trimIndent(),
        ) as List<Number>

        overlayBounds.map(Number::toDouble).shouldBe(listOf(0.0, 0.0, 390.0, 844.0))
    }

    private fun saveUser(username: String): User =
        userRepository.save(User(username = username, passwordHash = passwordHasher.hash("password"), type = UserType.USER))

    private fun saveAccount(
        user: User,
        name: String,
        initialBalanceMinor: Long,
        isDefault: Boolean = false,
        archived: Boolean = false,
    ): TrackingAccount =
        trackingAccountRepository.save(
            TrackingAccount(
                userId = user.id!!,
                name = name,
                currency = "AUD",
                initialBalanceMinor = initialBalanceMinor,
                isDefault = isDefault,
                archived = archived,
            ),
        )

    private fun saveExpenseCategory(user: User, name: String): ExpenseCategory =
        expenseCategoryRepository.save(ExpenseCategory(userId = user.id!!, name = name))

    private fun saveIncomeCategory(user: User, name: String): IncomeCategory =
        incomeCategoryRepository.save(IncomeCategory(userId = user.id!!, name = name))

    private fun saveTransaction(
        user: User,
        account: TrackingAccount,
        category: Any,
        type: TransactionType,
        date: LocalDate,
        amountMinor: Long,
    ): Transaction = transactionRepository.save(
        Transaction(
            userId = user.id!!,
            type = type,
            trackingAccountId = account.id!!,
            categoryId = when (category) {
                is ExpenseCategory -> category.id!!
                is IncomeCategory -> category.id!!
                else -> error("Unsupported category")
            },
            date = date,
            amountMinor = amountMinor,
            defaultCurrencyAmountMinor = amountMinor,
            defaultCurrency = "AUD",
            notes = null,
        ),
    )

    private fun saveTransfer(
        user: User,
        sourceAccount: TrackingAccount,
        targetAccount: TrackingAccount,
        date: LocalDate,
        sourceAmountMinor: Long,
        targetAmountMinor: Long,
    ): FundsTransfer = fundsTransferRepository.save(
        FundsTransfer(
            userId = user.id!!,
            sourceAccountId = sourceAccount.id!!,
            targetAccountId = targetAccount.id!!,
            sourceAmountMinor = sourceAmountMinor,
            targetAmountMinor = targetAmountMinor,
            date = date,
        ),
    )

    private fun Page.shouldEventuallyContainDashboardCards(vararg expectedCards: DashboardCardText) {
        shouldEventually {
            extractDashboardCards(this).shouldContainExactly(*expectedCards)
        }
    }

    private fun extractDashboardCards(page: Page): List<DashboardCardText> {
        @Suppress("UNCHECKED_CAST")
        val cards = page.locator("[data-testid='dashboard-account-card']").evaluateAll(
            "cards => cards.map(card => card.textContent.trim().replace(/\\s+/g, ' '))",
        ) as List<String>
        return cards.map(::DashboardCardText)
    }

    private fun multiDropdownRow(page: Page, option: String): Locator =
        page.locator(".searchable-multi-dropdown-row")
            .filter(Locator.FilterOptions().setHas(page.getByText(option, Page.GetByTextOptions().setExact(true))))


    private fun Page.shouldEventuallyContainChartPoints(chart: Locator, vararg expectedPoints: ChartPoint) {
        shouldEventually {
            @Suppress("UNCHECKED_CAST")
            val points = chart.locator("[data-testid='transaction-chart-point']").evaluateAll(
                """
                    points => points.map(point => ({
                        bucket: point.dataset.bucket,
                        currency: point.dataset.currency,
                        amountMinor: Number(point.dataset.amountMinor),
                    }))
                """.trimIndent(),
            ) as List<Map<String, Any>>
            points.map {
                ChartPoint(
                    bucket = it.getValue("bucket") as String,
                    currency = it.getValue("currency") as String,
                    amountMinor = (it.getValue("amountMinor") as Number).toLong(),
                )
            }.shouldContainExactly(*expectedPoints)
        }
    }

    private fun Page.shouldEventuallyContainCategoryRows(
        chart: Locator,
        vararg expectedRows: CategoryChartRow,
    ) {
        shouldEventually {
            @Suppress("UNCHECKED_CAST")
            val rows = chart.locator("[data-testid='transaction-category-chart-row']").evaluateAll(
                """
                    rows => rows.map(row => ({
                        categoryId: Number(row.dataset.categoryId),
                        categoryName: row.dataset.categoryName,
                        currency: row.dataset.currency,
                        amountMinor: Number(row.dataset.amountMinor),
                        visible: row.dataset.visible === 'true',
                    }))
                """.trimIndent(),
            ) as List<Map<String, Any>>
            rows.map {
                CategoryChartRow(
                    categoryId = (it.getValue("categoryId") as Number).toLong(),
                    categoryName = it.getValue("categoryName") as String,
                    currency = it.getValue("currency") as String,
                    amountMinor = (it.getValue("amountMinor") as Number).toLong(),
                    visible = it.getValue("visible") as Boolean,
                )
            }.shouldContainExactly(*expectedRows)
        }
    }
}

private data class DashboardCardText(val text: String)

private data class ChartPoint(val bucket: String, val currency: String, val amountMinor: Long)

private data class CategoryChartRow(
    val categoryId: Long,
    val categoryName: String,
    val currency: String,
    val amountMinor: Long,
    val visible: Boolean,
)
