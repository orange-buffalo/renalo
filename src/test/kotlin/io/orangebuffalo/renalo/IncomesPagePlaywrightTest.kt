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
        selectOption(page, "Income category", "Bonus")
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
        selectOption(page, "Income category", "Bonus")
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
    fun createsRecurringIncomeFromIncomeForm(page: Page) {
        val alice = saveUser("alice")
        saveAccount(alice, "Main", "AUD", isDefault = true)
        saveCategory(alice, "Salary")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes/create")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add income"))).isVisible()
        selectOption(page, "Income category", "Salary")
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

        applyDateFilterPreset(page, "This month", "All time")
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

        assertDateFilterLabel(page, "This month")
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Salary", "A$1,234.00", "Today", "Main", "This month income", "edit delete"),
        )

        applyDateFilterPreset(page, "This month", "Next month")
        assertDateFilterLabel(page, "Next month")
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Planned incomes", "A$2,000.00", "", "", "", "view"),
        )

        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Expenses")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        assertDateFilterLabel(page, "Next month")
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Planned expenses", "A$34.00", "", "", "", "view"),
        )

        page.reload()
        assertDateFilterLabel(page, "This month")
        page.shouldEventuallyContainExpenseRows(
            ExpenseRow("Groceries", "A$12.00", "Today", "Main", "This month expense", "edit delete"),
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
        saveIncome(alice, savings, bonus, TestTimeProvider.DEFAULT_DATE.plusDays(1), 20000, "Planned bonus")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/incomes")

        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Planned incomes", "A$100.00 €200.00", "", "", "", "view"),
            IncomeRow("Salary", "A$1,234.00", "Today", "Main", "Pay", "edit delete"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("View all planned incomes")).click()
        page.shouldEventuallyContainIncomeRows(
            IncomeRow("Salary", "A$100.00", "Jun 16", "Main", "Planned pay", "edit delete"),
            IncomeRow("Bonus", "€200.00", "Jun 15", "Savings", "Planned bonus", "edit delete"),
            IncomeRow("Salary", "A$1,234.00", "Today", "Main", "Pay", "edit delete"),
        )
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

    private fun saveCategory(user: User, name: String): IncomeCategory = incomeCategoryRepository.save(
        IncomeCategory(
            userId = user.id!!,
            name = name,
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
    ): Transaction = transactionRepository.save(
        Transaction(
            userId = user.id!!,
            type = TransactionType.INCOME,
            trackingAccountId = account.id!!,
            categoryId = category.id!!,
            date = date,
            amountMinor = amountMinor,
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

    private fun selectOption(page: Page, label: String, option: String) {
        page.getByLabel(label).click()
        page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName(option).setExact(true)).click()
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

    private data class ExpenseRow(
        val category: String,
        val amount: String,
        val date: String,
        val account: String,
        val notes: String,
        val action: String,
    )
}
