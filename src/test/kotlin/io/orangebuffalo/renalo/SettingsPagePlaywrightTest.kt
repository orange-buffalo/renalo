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
import io.orangebuffalo.renalo.test.shouldEventually
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
class SettingsPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var incomeCategoryRepository: IncomeCategoryRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun managesAccountsFromSettingsPage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        saveAccount(alice, "Savings", "EUR", 12345, isDefault = false)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings")

        assertThat(page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Accounts"))).isVisible()
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Expense Categories")).click()
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Expense categories"))).isVisible()
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Income Categories")).click()
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Income categories"))).isVisible()
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Accounts")).click()
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Tracking accounts"))).isVisible()
        page.shouldEventuallyContainRows(
            AccountRow("Main", "AUD", "A$0.00", "Default", "edit"),
            AccountRow("Savings", "EUR", "€123.45", "No", "edit"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add new account")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add new account"))).isVisible()
        page.getByLabel("Name").fill("Cash")
        page.locator("input[name='initialBalance']").fill("42")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create account")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Budget settings"))).isVisible()
        val cash = trackingAccountRepository.findByUserIdOrderByName(alice.id!!).first { it.name == "Cash" }
        cash.currency.shouldBe("AUD")
        cash.initialBalanceMinor.shouldBe(4200)
        cash.isDefault.shouldBe(false)
        page.shouldEventuallyContainRows(
            AccountRow("Cash", "AUD", "A$42.00", "No", "edit"),
            AccountRow("Main", "AUD", "A$0.00", "Default", "edit"),
            AccountRow("Savings", "EUR", "€123.45", "No", "edit"),
        )

        page.locator("[data-testid='account-row-${main.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Edit Main"))
            .click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Edit account"))).isVisible()
        assertThat(page.getByText("Changing currency will implicitly change the currency")).not().isVisible()
        selectCurrency(page, "Euro")
        assertThat(page.getByText("Changing currency will implicitly change the currency")).isVisible()
        selectCurrency(page, "Australian Dollar")
        assertThat(page.getByText("Changing currency will implicitly change the currency")).not().isVisible()
        assertThat(page.getByText("To change the default, nominate another account.")).isVisible()
        assertThat(page.getByLabel("Default account")).isDisabled()
        page.getByLabel("Name").fill("Everyday")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save account")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Budget settings"))).isVisible()
        trackingAccountRepository.findById(main.id!!).get().name.shouldBe("Everyday")
        page.shouldEventuallyContainRows(
            AccountRow("Cash", "AUD", "A$42.00", "No", "edit"),
            AccountRow("Everyday", "AUD", "A$0.00", "Default", "edit"),
            AccountRow("Savings", "EUR", "€123.45", "No", "edit"),
        )
    }

    @Test
    fun showsTrackingAccountsAsExpandableMobileCards(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        saveAccount(alice, "Savings", "EUR", 12345, isDefault = false)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.setViewportSize(390, 844)

        page.navigate(server.url.toString() + "/settings")

        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Tracking accounts"))).isVisible()
        page.shouldEventuallyContainRows(
            AccountRow("Main", "AUD", "A$0.00", "Default", "edit"),
            AccountRow("Savings", "EUR", "€123.45", "No", "edit"),
        )

        val mainCard = page.locator("[data-testid='account-row-${main.id}']")
        assertThat(mainCard.getByText("Main")).isVisible()
        assertThat(mainCard.getByText("AUD")).isVisible()
        assertThat(mainCard.getByText("A$0.00")).not().isVisible()
        assertThat(mainCard.getByText("Default")).not().isVisible()
        mainCard.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Show Main details")).click()

        val initialBalanceDetail = mainCard.locator("[data-mobile-label='Initial balance']")
        assertThat(initialBalanceDetail).isVisible()
        initialBalanceDetail.evaluate("element => getComputedStyle(element, '::before').content").shouldBe("\"Initial balance\"")
        assertThat(mainCard.getByText("A$0.00")).isVisible()
        assertThat(mainCard.getByText("Default")).isVisible()
        assertThat(mainCard.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Edit Main"))).isVisible()
    }

    @Test
    fun cancelsAccountCreateBackToSettings(page: Page) {
        val alice = saveUser("alice")
        saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings/accounts/create")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Cancel")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Budget settings"))).isVisible()
        assertThat(page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Accounts"))).isVisible()
    }

    @Test
    fun validatesAccountForm(page: Page) {
        val alice = saveUser("alice")
        saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings/accounts/create")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create account")).click()

        assertThat(page.getByText("Enter an account name.")).isVisible()
        page.getByLabel("Name").fill("Cash")
        page.locator("input[name='initialBalance']").fill("abc")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create account")).click()

        assertThat(page.getByText("Enter a valid initial amount.")).isVisible()
        trackingAccountRepository.findByUserIdOrderByName(alice.id!!).map { it.name }.shouldContainExactly("Main")
    }

    @Test
    fun managesExpenseCategoriesFromSettingsPage(page: Page) {
        val alice = saveUser("alice")
        val groceries = saveCategory(alice, "Groceries")
        saveCategory(alice, "Rent")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings?tab=expense-categories")

        assertThat(page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Expense Categories"))).isVisible()
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Expense categories"))).isVisible()
        page.shouldEventuallyContainCategoryRows(
            CategoryRow("Groceries", "edit"),
            CategoryRow("Rent", "edit"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add new category")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add new expense category"))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create category")).click()
        assertThat(page.getByText("Enter an expense category name.")).isVisible()
        page.getByLabel("Name").fill("Utilities")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create category")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Budget settings"))).isVisible()
        assertThat(page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Expense Categories"))).isVisible()
        expenseCategoryRepository.findByUserIdOrderByName(alice.id!!).map { it.name }.shouldContainExactly(
            "Groceries",
            "Rent",
            "Utilities",
        )
        page.shouldEventuallyContainCategoryRows(
            CategoryRow("Groceries", "edit"),
            CategoryRow("Rent", "edit"),
            CategoryRow("Utilities", "edit"),
        )

        page.locator("[data-testid='expense-category-row-${groceries.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Edit Groceries"))
            .click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Edit Groceries"))).isVisible()
        page.getByLabel("Name").fill("Food")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save category")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Budget settings"))).isVisible()
        expenseCategoryRepository.findById(groceries.id!!).get().name.shouldBe("Food")
        page.shouldEventuallyContainCategoryRows(
            CategoryRow("Food", "edit"),
            CategoryRow("Rent", "edit"),
            CategoryRow("Utilities", "edit"),
        )
    }

    @Test
    fun managesIncomeCategoriesFromSettingsPage(page: Page) {
        val alice = saveUser("alice")
        val salary = saveIncomeCategory(alice, "Salary")
        saveIncomeCategory(alice, "Interest")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings?tab=income-categories")

        assertThat(page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Income Categories"))).isVisible()
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Income categories"))).isVisible()
        page.shouldEventuallyContainIncomeCategoryRows(
            CategoryRow("Interest", "edit"),
            CategoryRow("Salary", "edit"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add new category")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add new income category"))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create category")).click()
        assertThat(page.getByText("Enter an income category name.")).isVisible()
        page.getByLabel("Name").fill("Bonus")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create category")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Budget settings"))).isVisible()
        assertThat(page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Income Categories"))).isVisible()
        incomeCategoryRepository.findByUserIdOrderByName(alice.id!!).map { it.name }.shouldContainExactly(
            "Bonus",
            "Interest",
            "Salary",
        )
        page.shouldEventuallyContainIncomeCategoryRows(
            CategoryRow("Bonus", "edit"),
            CategoryRow("Interest", "edit"),
            CategoryRow("Salary", "edit"),
        )

        page.locator("[data-testid='income-category-row-${salary.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Edit Salary"))
            .click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Edit Salary"))).isVisible()
        page.getByLabel("Name").fill("Payroll")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save category")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Budget settings"))).isVisible()
        incomeCategoryRepository.findById(salary.id!!).get().name.shouldBe("Payroll")
        page.shouldEventuallyContainIncomeCategoryRows(
            CategoryRow("Bonus", "edit"),
            CategoryRow("Interest", "edit"),
            CategoryRow("Payroll", "edit"),
        )
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
        initialBalanceMinor: Long,
        isDefault: Boolean,
    ): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = currency,
            initialBalanceMinor = initialBalanceMinor,
            isDefault = isDefault,
        ),
    )

    private fun saveCategory(user: User, name: String): ExpenseCategory = expenseCategoryRepository.save(
        ExpenseCategory(
            userId = user.id!!,
            name = name,
        ),
    )

    private fun saveIncomeCategory(user: User, name: String): IncomeCategory = incomeCategoryRepository.save(
        IncomeCategory(
            userId = user.id!!,
            name = name,
        ),
    )

    private fun extractAccountRows(page: Page): List<AccountRow> {
        @Suppress("UNCHECKED_CAST")
        val rows = page.locator("[data-testid^='account-row-']").evaluateAll(
            """
                rows => rows.map(row => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'))
                    .map(cell => {
                        const actions = Array.from(cell.querySelectorAll('[data-action-icon]'))
                            .map(icon => icon.dataset.actionIcon);
                        return actions.length ? actions.join(' ') : cell.textContent.trim();
                    }))
            """.trimIndent(),
        ) as List<List<String>>

        return rows.map { cells ->
            AccountRow(
                name = cells.getOrElse(0) { "" },
                currency = cells.getOrElse(1) { "" },
                initialBalance = cells.getOrElse(2) { "" },
                default = cells.getOrElse(3) { "" },
                action = cells.getOrElse(4) { "" },
            )
        }
    }

    private fun Page.shouldEventuallyContainRows(vararg expectedRows: AccountRow) {
        shouldEventually {
            extractAccountRows(this).shouldContainExactly(*expectedRows)
        }
    }

    private fun extractCategoryRows(page: Page): List<CategoryRow> {
        @Suppress("UNCHECKED_CAST")
        val rows = page.locator("[data-testid^='expense-category-row-']").evaluateAll(
            """
                rows => rows.map(row => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'))
                    .map(cell => {
                        const actions = Array.from(cell.querySelectorAll('[data-action-icon]'))
                            .map(icon => icon.dataset.actionIcon);
                        return actions.length ? actions.join(' ') : cell.textContent.trim();
                    }))
            """.trimIndent(),
        ) as List<List<String>>

        return rows.map { cells ->
            CategoryRow(
                name = cells.getOrElse(0) { "" },
                action = cells.getOrElse(1) { "" },
            )
        }
    }

    private fun Page.shouldEventuallyContainCategoryRows(vararg expectedRows: CategoryRow) {
        shouldEventually {
            extractCategoryRows(this).shouldContainExactly(*expectedRows)
        }
    }

    private fun extractIncomeCategoryRows(page: Page): List<CategoryRow> {
        @Suppress("UNCHECKED_CAST")
        val rows = page.locator("[data-testid^='income-category-row-']").evaluateAll(
            """
                rows => rows.map(row => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'))
                    .map(cell => {
                        const actions = Array.from(cell.querySelectorAll('[data-action-icon]'))
                            .map(icon => icon.dataset.actionIcon);
                        return actions.length ? actions.join(' ') : cell.textContent.trim();
                    }))
            """.trimIndent(),
        ) as List<List<String>>

        return rows.map { cells ->
            CategoryRow(
                name = cells.getOrElse(0) { "" },
                action = cells.getOrElse(1) { "" },
            )
        }
    }

    private fun Page.shouldEventuallyContainIncomeCategoryRows(vararg expectedRows: CategoryRow) {
        shouldEventually {
            extractIncomeCategoryRows(this).shouldContainExactly(*expectedRows)
        }
    }

    private fun selectCurrency(page: Page, search: String) {
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Currency")).click()
        page.getByLabel("Search currencies").fill(search)
        page.getByText(java.util.regex.Pattern.compile(search)).last().click()
    }
}

private data class AccountRow(
    val name: String,
    val currency: String,
    val initialBalance: String,
    val default: String,
    val action: String,
)

private data class CategoryRow(
    val name: String,
    val action: String,
)
