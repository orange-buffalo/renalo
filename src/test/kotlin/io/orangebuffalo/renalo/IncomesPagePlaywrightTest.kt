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
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.IncomeCategory
import io.orangebuffalo.renalo.tracking.IncomeCategoryRepository
import io.orangebuffalo.renalo.tracking.RecurringTransactionRuleRepository
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
class IncomesPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var incomeCategoryRepository: IncomeCategoryRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var recurringTransactionRuleRepository: RecurringTransactionRuleRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun managesIncomesFromIncomesPage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        saveAccount(alice, "Savings", "EUR", isDefault = false)
        val salary = saveCategory(alice, "Salary")
        val bonus = saveCategory(alice, "Bonus")
        val oldCategory = saveCategory(alice, "Old category", archived = true)
        val todayIncome = saveIncome(alice, main, salary, TestTimeProvider.DEFAULT_DATE, 123400, "Pay")
        saveIncome(alice, main, bonus, TestTimeProvider.DEFAULT_DATE.minusDays(1), 25000, null)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Incomes"))).isVisible()
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Salary", "A$1,234.00", "Today", "Main", "Pay", "edit delete"),
            IncomeRow("Bonus", "A$250.00", "Yesterday", "Main", "-", "edit delete"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add income")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add income"))).isVisible()
        assertThat(page.getByText("Record earnings against an account and income category.")).isVisible()
        selectCategoryOption(page, "Income category", "Bonus")
        page.locator("input[name='amount']").fill("42")
        page.getByLabel("Notes").fill("Side project")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create income")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Incomes"))).isVisible()
        val sideProjectIncome = transactionRepository.findByUserIdAndTypeOrderByDateDesc(alice.id!!, TransactionType.INCOME)
            .first { it.notes == "Side project" }
        sideProjectIncome.amountMinor.shouldBe(4200)
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Salary", "A$1,234.00", "Today", "Main", "Pay", "edit delete"),
            IncomeRow("Bonus", "A$42.00", "Today", "Main", "Side project", "edit delete"),
            IncomeRow("Bonus", "A$250.00", "Yesterday", "Main", "-", "edit delete"),
        )

        page.locator("[data-testid='income-row-${todayIncome.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Edit Salary income"))
            .click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Edit income"))).isVisible()
        selectCategoryOption(page, "Income category", "Bonus")
        page.locator("input[name='amount']").fill("1500")
        page.getByLabel("Notes").fill("Monthly salary")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save income")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Incomes"))).isVisible()
        transactionRepository.findById(todayIncome.id!!).get().amountMinor.shouldBe(150000)
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Bonus", "A$1,500.00", "Today", "Main", "Monthly salary", "edit delete"),
            IncomeRow("Bonus", "A$42.00", "Today", "Main", "Side project", "edit delete"),
            IncomeRow("Bonus", "A$250.00", "Yesterday", "Main", "-", "edit delete"),
        )

        page.locator("[data-testid='income-row-${todayIncome.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Delete Bonus income"))
            .click()
        assertThat(page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Delete Bonus income?"))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Delete income")).click()

        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Bonus", "A$42.00", "Today", "Main", "Side project", "edit delete"),
            IncomeRow("Bonus", "A$250.00", "Yesterday", "Main", "-", "edit delete"),
        )
        transactionRepository.findById(todayIncome.id!!).isPresent.shouldBe(false)
    }

    @Test
    fun validatesIncomeFormRequiredFieldsAndAllowsOptionalNotes(page: Page) {
        val alice = saveUser("alice")
        saveAccount(alice, "Main", "AUD", isDefault = true)
        saveCategory(alice, "Salary")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes/create")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add income"))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create income")).click()

        assertThat(page.getByText("Choose an income category.")).isVisible()
        assertThat(page.getByText("Enter a valid amount greater than zero.")).isVisible()
        assertRequiredLabel(page, "Amount")

        selectCategoryOption(page, "Income category", "Salary")
        page.locator("input[name='amount']").fill("123.45")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create income")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Incomes"))).isVisible()
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Salary", "A$123.45", "Today", "Main", "-", "edit delete"),
        )
        transactionRepository.findByUserIdAndTypeOrderByDateDesc(alice.id!!, TransactionType.INCOME)
            .single()
            .notes
            .shouldBe(null)
    }

    @Test
    fun createsRecurringIncomeFromIncomeForm(page: Page) {
        val alice = saveUser("alice")
        saveAccount(alice, "Main", "AUD", isDefault = true)
        saveCategory(alice, "Salary")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes/create")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add income"))).isVisible()
        selectCategoryOption(page, "Income category", "Salary")
        page.locator("input[name='amount']").fill("1234")
        page.getByLabel("Notes").fill("Monthly salary")
        page.getByLabel("Recurring income").press("Space")
        assertThat(page.getByText("Generate matching income rows from this schedule.")).isVisible()
        selectOption(page, "Repeat", "Monthly")
        selectOption(page, "End after repetitions", "3")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create income")).click()

        page.waitForURL("**/incomes")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Incomes"))).isVisible()
        val rule = recurringTransactionRuleRepository.findAll().single()
        rule.transactionType.shouldBe(TransactionType.INCOME)
        rule.recurrenceFrequency.shouldBe(1)
        rule.recurrenceInterval.shouldBe(RecurrenceInterval.MONTH)
        rule.endDate.shouldBe(TestTimeProvider.DEFAULT_DATE.plusMonths(2))
        transactionRepository.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!)
            .map { it.type to it.recurringInstanceDate }
            .shouldContainExactly(
                TransactionType.INCOME to TestTimeProvider.DEFAULT_DATE,
                TransactionType.INCOME to TestTimeProvider.DEFAULT_DATE.plusMonths(1),
                TransactionType.INCOME to TestTimeProvider.DEFAULT_DATE.plusMonths(2),
            )
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Salary", "A$1,234.00", "Today Repeats monthly until 14 Aug 2099", "Main", "Monthly salary", "edit delete"),
        )

        applyDateFilterPreset(page, "June 2099", "All time")
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Salary", "A$1,234.00", "Today Repeats monthly until 14 Aug 2099", "Main", "Monthly salary", "edit delete"),
            IncomeRow("Planned incomes", "A$2,468.00", "", "", "", "view"),
        )
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("View all planned incomes")).click()
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Salary", "A$1,234.00", "Today Repeats monthly until 14 Aug 2099", "Main", "Monthly salary", "edit delete"),
            IncomeRow("Salary", "A$1,234.00", "Jul 14 Repeats monthly until 14 Aug 2099", "Main", "Monthly salary", "edit delete"),
            IncomeRow("Salary", "A$1,234.00", "Aug 14 Repeats monthly until 14 Aug 2099", "Main", "Monthly salary", "edit delete"),
        )
    }

    @Test
    fun sharesDateFilterBetweenIncomeAndExpensePages(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val salary = saveCategory(alice, "Salary")
        val groceries = saveExpenseCategory(alice, "Groceries")
        saveIncome(alice, main, salary, TestTimeProvider.DEFAULT_DATE, 123400, "This month income")
        saveIncome(alice, main, salary, TestTimeProvider.DEFAULT_DATE.plusMonths(1), 200000, "Next month income")
        saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE, 1200, "This month expense")
        saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE.plusMonths(1), 3400, "Next month expense")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes")

        assertDateFilterLabel(page, "June 2099")
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Salary", "A$1,234.00", "Today", "Main", "This month income", "edit delete"),
        )

        applyDateFilterPreset(page, "June 2099", "Next month")
        assertDateFilterLabel(page, "July 2099")
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Planned incomes", "A$2,000.00", "", "", "", "view"),
        )

        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Expenses")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        assertDateFilterLabel(page, "July 2099")
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Planned expenses", "A$34.00", "", "", "", "view"),
        )

        page.reload()
        assertDateFilterLabel(page, "June 2099")
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$12.00", "Today", "Main", "This month expense", "edit delete"),
        )
    }

    @Test
    fun keepsSecondaryFiltersLocalToIncomePage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val savings = saveAccount(alice, "Savings", "AUD", isDefault = false)
        val archived = saveAccount(alice, "Old", "AUD", isDefault = false, archived = true)
        val salary = saveCategory(alice, "Salary")
        val bonus = saveCategory(alice, "Bonus")
        val oldCategory = saveCategory(alice, "Old category", archived = true)
        val groceries = saveExpenseCategory(alice, "Groceries")
        saveIncome(alice, main, salary, TestTimeProvider.DEFAULT_DATE, 123400, "Monthly consulting pay")
        saveIncome(alice, main, salary, TestTimeProvider.DEFAULT_DATE, 200000, "Monthly payroll")
        saveIncome(alice, savings, salary, TestTimeProvider.DEFAULT_DATE, 300000, "Monthly consulting savings")
        saveIncome(alice, archived, salary, TestTimeProvider.DEFAULT_DATE, 500000, "Monthly consulting old")
        saveIncome(alice, main, bonus, TestTimeProvider.DEFAULT_DATE, 400000, "Monthly consulting bonus")
        saveIncome(alice, main, oldCategory, TestTimeProvider.DEFAULT_DATE, 600000, "Monthly consulting old category")
        saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE, 1200, "Monthly consulting supplies")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes")

        openMoreFilters(page)
        val filtersDialog = page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("More filters"))
        assertThat(filtersDialog.getByText("Old")).not().isVisible()
        filtersDialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Income category").setExact(true)).click()
        assertThat(dropdownOption(page, "Salary")).isVisible()
        assertThat(dropdownOption(page, "Old category")).not().isVisible()
        dropdownOption(page, "Salary").click()
        page.keyboard().press("Escape")
        selectMoreFilterOption(page, "Account", "Main")
        page.getByLabel("Notes").fill("monthly consulting")

        assertThat(page.locator(".transaction-filter-count-badge")).hasText("3")
        assertThat(page.getByLabel("Selected income category").getByText("Salary")).isVisible()
        assertThat(page.getByLabel("Selected account").getByText("Main")).isVisible()
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Salary", "A$1,234.00", "Today", "Main", "Monthly consulting pay", "edit delete"),
        )

        page.keyboard().press("Escape")
        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Expenses")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        assertThat(page.locator(".transaction-filter-count-badge")).not().isVisible()
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$12.00", "Today", "Main", "Monthly consulting supplies", "edit delete"),
        )
    }

    @Test
    fun groupsFutureIncomesByCurrencyUntilViewed(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val savings = saveAccount(alice, "Savings", "EUR", isDefault = false)
        val salary = saveCategory(alice, "Salary")
        val bonus = saveCategory(alice, "Bonus")
        saveIncome(alice, main, salary, TestTimeProvider.DEFAULT_DATE, 123400, "Pay")
        saveIncome(alice, main, salary, TestTimeProvider.DEFAULT_DATE.plusDays(2), 10000, "Planned pay")
        saveIncome(
            alice,
            savings,
            bonus,
            TestTimeProvider.DEFAULT_DATE.plusDays(1),
            20000,
            "Planned bonus",
            defaultCurrencyAmountMinor = 33000,
        )
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes")

        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Planned incomes", "A$100.00 €200.00", "", "", "", "view"),
            IncomeRow("Salary", "A$1,234.00", "Today", "Main", "Pay", "edit delete"),
        )
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Hide chart"))).hasAttribute(
            "aria-pressed",
            "true",
        )
        page.shouldEventuallyContainChartPoints(
            ChartPoint("2099-06-14", "AUD", 123400),
            ChartPoint("2099-06-15", "AUD", 33000),
            ChartPoint("2099-06-16", "AUD", 10000),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("View all planned incomes")).click()
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Salary", "A$100.00", "Jun 16", "Main", "Planned pay", "edit delete"),
            IncomeRow("Bonus", "€200.00", "Jun 15", "Savings", "Planned bonus", "edit delete"),
            IncomeRow("Salary", "A$1,234.00", "Today", "Main", "Pay", "edit delete"),
        )
    }

    @Test
    fun groupsTransactionsAndPersistsEachPageSelectionIndependently(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val usdAccount = saveAccount(alice, "USD account", "USD", isDefault = false)
        val salary = saveCategory(alice, "Salary")
        val bonus = saveCategory(alice, "Bonus")
        val groceries = saveExpenseCategory(alice, "Groceries")
        saveIncome(alice, main, salary, TestTimeProvider.DEFAULT_DATE, 123400, "Pay")
        saveIncome(alice, main, bonus, TestTimeProvider.DEFAULT_DATE.minusDays(1), 25000, "Bonus")
        saveIncome(alice, main, salary, TestTimeProvider.DEFAULT_DATE, 4200, "Side project")
        saveIncome(alice, usdAccount, salary, TestTimeProvider.DEFAULT_DATE, 1000, "USD project")
        saveExpense(alice, main, groceries, TestTimeProvider.DEFAULT_DATE, 1200, "Supplies")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes")

        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Grouping: Group by date"))).isVisible()
        page.shouldEventuallyContainTransactionGroups("income", "Today 1,276.00 AUD, 10.00 USD", "Yesterday 250.00 AUD")

        selectGrouping(page, "Group by date", "Group by category")
        page.shouldEventuallyContainTransactionGroups("income", "Salary 1,276.00 AUD, 10.00 USD", "Bonus 250.00 AUD")
        page.evaluate("window.localStorage.getItem('renalo.incomes.tableGrouping')").shouldBe("category")

        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Expenses")).click()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Grouping: Group by date"))).isVisible()
        page.shouldEventuallyContainTransactionGroups("expense", "Today 12.00 AUD")

        selectGrouping(page, "Group by date", "Plain list")
        page.shouldEventuallyContainTransactionGroups("expense")
        page.reload()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Grouping: Plain list"))).isVisible()
        page.shouldEventuallyContainTransactionGroups("expense")

        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Incomes")).click()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Grouping: Group by category"))).isVisible()
        page.shouldEventuallyContainTransactionGroups("income", "Salary 1,276.00 AUD, 10.00 USD", "Bonus 250.00 AUD")
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

    private fun saveCategory(user: User, name: String, archived: Boolean = false): IncomeCategory = incomeCategoryRepository.save(
        IncomeCategory(
            userId = user.id!!,
            name = name,
            archived = archived,
        ),
    )

    private fun saveExpenseCategory(user: User, name: String): ExpenseCategory = expenseCategoryRepository.save(
        ExpenseCategory(
            userId = user.id!!,
            name = name,
        ),
    )

    private fun saveIncome(
        user: User,
        account: TrackingAccount,
        category: IncomeCategory,
        date: LocalDate,
        amountMinor: Long,
        notes: String?,
        defaultCurrencyAmountMinor: Long? = if (account.currency == "AUD") amountMinor else null,
    ): Transaction = transactionRepository.save(
        Transaction(
            userId = user.id!!,
            type = TransactionType.INCOME,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            date = date,
            amountMinor = amountMinor,
            defaultCurrencyAmountMinor = defaultCurrencyAmountMinor,
            defaultCurrency = "AUD",
            notes = notes,
        ),
    )

    private fun saveExpense(
        user: User,
        account: TrackingAccount,
        category: ExpenseCategory,
        date: LocalDate,
        amountMinor: Long,
        notes: String?,
    ): Transaction = transactionRepository.save(
        Transaction(
            userId = user.id!!,
            type = TransactionType.EXPENSE,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            date = date,
            amountMinor = amountMinor,
            defaultCurrencyAmountMinor = amountMinor,
            defaultCurrency = "AUD",
            notes = notes,
        ),
    )

    private fun assertDateFilterLabel(page: Page, label: String) {
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(label).setExact(true))).isVisible()
    }

    private fun applyDateFilterPreset(page: Page, currentLabel: String, preset: String) {
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(currentLabel).setExact(true)).click()
        val dialog = page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Date range filter"))
        dialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(preset).setExact(true)).click()
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

    private fun selectGrouping(page: Page, currentGrouping: String, nextGrouping: String) {
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Grouping: $currentGrouping")).click()
        page.getByText(nextGrouping, Page.GetByTextOptions().setExact(true)).click()
    }

    private fun Page.shouldEventuallyContainTransactionGroups(prefix: String, vararg expectedLabels: String) {
        shouldEventually {
            val labels = locator("[data-testid^='$prefix-group-']").allInnerTexts().map {
                it.trim().replace(Regex("\\s+"), " ")
            }
            labels.shouldContainExactly(*expectedLabels)
        }
    }

    private fun Page.shouldEventuallyContainChartPoints(vararg expectedPoints: ChartPoint) {
        shouldEventually {
            @Suppress("UNCHECKED_CAST")
            val points = locator("[data-testid='transaction-chart-point']").evaluateAll(
                "points => points.map(point => [point.dataset.bucket, point.dataset.currency, point.dataset.amountMinor].join('|'))",
            ) as List<String>
            points.map { point ->
                val (bucket, currency, amountMinor) = point.split("|")
                ChartPoint(bucket, currency, amountMinor.toLong())
            }.shouldContainExactly(*expectedPoints)
        }
    }

    @Test
    fun persistsSelectedIncomeAccountBetweenNavigations(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val savings = saveAccount(alice, "Savings", "EUR", isDefault = false)
        saveCategory(alice, "Salary")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add income"))).isVisible()
        assertThat(page.getByLabel("Account").last()).containsText("Main")

        page.getByLabel("Account").last().click()
        dropdownOption(page, "Savings").click()
        assertThat(page.getByLabel("Account").last()).containsText("Savings")

        page.navigate(server.url.toString() + "/incomes")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Incomes"))).isVisible()

        page.navigate(server.url.toString() + "/incomes/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add income"))).isVisible()
        assertThat(page.getByLabel("Account").last()).containsText("Savings")
    }

    @Test
    fun showsCategoriesInUsageOrderOnIncomeForm(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val salary = saveCategory(alice, "Salary")
        val bonus = saveCategory(alice, "Bonus")
        val interest = saveCategory(alice, "Interest")
        saveIncome(alice, main, salary, TestTimeProvider.DEFAULT_DATE, 1000, "Salary")
        saveIncome(alice, main, bonus, TestTimeProvider.DEFAULT_DATE.minusDays(5), 2000, "Bonus")
        saveIncome(alice, main, interest, TestTimeProvider.DEFAULT_DATE.minusDays(10), 3000, "Interest")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add income"))).isVisible()

        page.getByLabel("Income category").click()
        val optionOrder = dropdownOptions(page).evaluateAll(
            "options => options.map(o => o.textContent.trim())",
        ) as List<String>
        optionOrder.shouldBe(listOf("Salary", "Bonus", "Interest"))
        page.keyboard().press("Escape")
    }

    @Test
    fun keepsCategoryEmptyOnIncomeCreateForm(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        saveCategory(alice, "Salary")
        saveCategory(alice, "Bonus")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add income"))).isVisible()
        assertThat(page.getByLabel("Income category")).containsText("Choose income category")
    }

    @Test
    fun searchesCategoriesOnIncomeForm(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        saveCategory(alice, "Salary")
        saveCategory(alice, "Bonus")
        saveCategory(alice, "Interest")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add income"))).isVisible()

        page.getByLabel("Income category").click()
        page.getByLabel("Search income category").fill("Bonus")
        assertThat(dropdownOption(page, "Bonus")).isVisible()
        assertThat(dropdownOption(page, "Salary")).not().isVisible()
        assertThat(dropdownOption(page, "Interest")).not().isVisible()
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
        page.locator(".searchable-dropdown-popover").getByText(option, Locator.GetByTextOptions().setExact(true))

    private fun assertRequiredLabel(page: Page, label: String) {
        assertThat(
            page.locator("label")
                .filter(Locator.FilterOptions().setHasText(label))
                .locator("span")
                .filter(Locator.FilterOptions().setHasText("*")),
        ).isVisible()
    }

    private fun extractIncomeRows(page: Page): List<IncomeRow> {
        @Suppress("UNCHECKED_CAST")
        val rows = page.locator("[data-testid^='income-row-']").evaluateAll(
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
            IncomeRow(
                category = cells.getOrElse(0) { "" },
                amount = cells.getOrElse(1) { "" },
                date = cells.getOrElse(2) { "" },
                account = cells.getOrElse(3) { "" },
                notes = cells.getOrElse(4) { "" },
                action = cells.getOrElse(5) { "" },
            )
        }
    }

    private fun Page.shouldEventuallyContainIncomeRows(vararg expectedRows: IncomeRow) {
        shouldEventually {
            extractIncomeRows(this).shouldContainExactlyInAnyOrder(*expectedRows)
        }
    }

    private fun Page.shouldEventuallyContainExpenseRows(vararg expectedRows: ExpenseRow) {
        shouldEventually {
            extractExpenseRows(this).shouldContainExactlyInAnyOrder(*expectedRows)
        }
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

    private data class IncomeRow(
        val category: String,
        val amount: String,
        val date: String,
        val account: String,
        val notes: String,
        val action: String,
    )

    private data class ChartPoint(
        val bucket: String,
        val currency: String,
        val amountMinor: Long,
    )

    private data class ExpenseRow(
        val category: String,
        val amount: String,
        val date: String,
        val account: String,
        val notes: String,
        val action: String,
    )
}
