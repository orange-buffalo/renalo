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
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.LocalDate

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
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var fundsTransferRepository: FundsTransferRepository

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
        assertThat(page.getByText("No expense categories found")).isVisible()
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Income Categories")).click()
        assertThat(page.getByText("No income categories found")).isVisible()
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Accounts")).click()
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Tracking accounts"))).isVisible()
        page.shouldEventuallyContainRows(
            AccountRow("Main", "AUD", "A$0.00", "Default", "Active", "archive merge adjust edit"),
            AccountRow("Savings", "EUR", "€123.45", "No", "Active", "archive merge adjust edit"),
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
            AccountRow("Cash", "AUD", "A$42.00", "No", "Active", "archive merge adjust edit"),
            AccountRow("Main", "AUD", "A$0.00", "Default", "Active", "archive merge adjust edit"),
            AccountRow("Savings", "EUR", "€123.45", "No", "Active", "archive merge adjust edit"),
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
            AccountRow("Cash", "AUD", "A$42.00", "No", "Active", "archive merge adjust edit"),
            AccountRow("Everyday", "AUD", "A$0.00", "Default", "Active", "archive merge adjust edit"),
            AccountRow("Savings", "EUR", "€123.45", "No", "Active", "archive merge adjust edit"),
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
            AccountRow("Main", "AUD", "A$0.00", "Default", "Active", "archive merge adjust edit"),
            AccountRow("Savings", "EUR", "€123.45", "No", "Active", "archive merge adjust edit"),
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
    fun archivesAndUnarchivesTrackingAccountFromSettingsPage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        saveAccount(alice, "Savings", "EUR", 12345, isDefault = false)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings")

        val mainRow = page.locator("[data-testid='account-row-${main.id}']")
        mainRow.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Archive Main")).click()
        val dialog = page.getByRole(AriaRole.DIALOG)
        assertThat(dialog.getByText("Archive account?")).isVisible()
        assertThat(dialog.getByText("Main will be hidden from dashboards, transaction forms, and account filters.")).isVisible()
        dialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Archive account")).click()

        page.shouldEventuallyContainRows(
            AccountRow("Main", "AUD", "A$0.00", "Default", "Archived", "archive merge adjust edit"),
            AccountRow("Savings", "EUR", "€123.45", "No", "Active", "archive merge adjust edit"),
        )
        trackingAccountRepository.findById(main.id!!).get().archived.shouldBe(true)

        mainRow.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Unarchive Main")).click()

        page.shouldEventuallyContainRows(
            AccountRow("Main", "AUD", "A$0.00", "Default", "Active", "archive merge adjust edit"),
            AccountRow("Savings", "EUR", "€123.45", "No", "Active", "archive merge adjust edit"),
        )
        trackingAccountRepository.findById(main.id!!).get().archived.shouldBe(false)
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
    fun mergesTrackingAccountFromSettingsPage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", 100, isDefault = true)
        val savings = saveAccount(alice, "Savings", "AUD", 500, isDefault = false)
        val external = saveAccount(alice, "External", "AUD", 0, isDefault = false)
        val expenseCategory = saveCategory(alice, "Groceries")
        val incomeCategory = saveIncomeCategory(alice, "Salary")
        val expense = saveTransaction(alice, main, expenseCategory, TransactionType.EXPENSE, 1_000)
        val income = saveTransaction(alice, main, incomeCategory, TransactionType.INCOME, 2_000)
        val outgoing = saveTransfer(alice, main, external, 300, 300)
        val internal = saveTransfer(alice, main, savings, 400, 400)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings")
        page.locator("[data-testid='account-row-${main.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Merge Main"))
            .click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Merge Main"))).isVisible()
        assertThat(page.getByText("Account merges are irreversible.")).isVisible()
        val mergePage = page.getByRole(AriaRole.MAIN)
        assertThat(mergePage.getByText("Expenses")).isVisible()
        assertThat(mergePage.getByText("Income")).isVisible()
        assertThat(mergePage.getByText("Transfers")).isVisible()
        assertThat(page.getByText("Main will be merged into External")).isVisible()

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Merge into account")).click()
        page.getByText("Savings").click()
        assertThat(page.getByText("Main will be merged into Savings")).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Merge account")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Budget settings"))).isVisible()
        trackingAccountRepository.findByIdAndUserId(main.id!!, alice.id!!).shouldBe(null)
        val mergedSavings = trackingAccountRepository.findById(savings.id!!).get()
        mergedSavings.initialBalanceMinor.shouldBe(600)
        mergedSavings.isDefault.shouldBe(true)
        transactionRepository.findById(expense.id!!).get().trackingAccountId.shouldBe(savings.id)
        transactionRepository.findById(income.id!!).get().trackingAccountId.shouldBe(savings.id)
        fundsTransferRepository.findById(outgoing.id!!).get().sourceAccountId.shouldBe(savings.id)
        fundsTransferRepository.findById(internal.id!!).isPresent.shouldBe(false)
    }

    @Test
    fun explainsWhenTrackingAccountHasNoCompatibleMergeTarget(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", 100, isDefault = true)
        saveAccount(alice, "Euro", "EUR", 500, isDefault = false)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings/accounts/${main.id}/merge")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Merge Main"))).isVisible()
        assertThat(page.getByText("No compatible account available")).isVisible()
        assertThat(page.getByText("Create another AUD account before merging this account.")).isVisible()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Merge account"))).isDisabled()
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
            CategoryRow("Groceries", "Active", "archive merge edit"),
            CategoryRow("Rent", "Active", "archive merge edit"),
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
            CategoryRow("Groceries", "Active", "archive merge edit"),
            CategoryRow("Rent", "Active", "archive merge edit"),
            CategoryRow("Utilities", "Active", "archive merge edit"),
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
            CategoryRow("Food", "Active", "archive merge edit"),
            CategoryRow("Rent", "Active", "archive merge edit"),
            CategoryRow("Utilities", "Active", "archive merge edit"),
        )
    }

    @Test
    fun mergesExpenseCategoryFromSettingsPage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        val groceries = saveCategory(alice, "Groceries")
        val food = saveCategory(alice, "Food")
        val expense = saveTransaction(alice, main, groceries, TransactionType.EXPENSE, 1_000)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings?tab=expense-categories")
        page.locator("[data-testid='expense-category-row-${groceries.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Merge Groceries"))
            .click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Merge Groceries"))).isVisible()
        assertThat(page.getByText("Category merges are irreversible.")).isVisible()
        val mergePage = page.getByRole(AriaRole.MAIN)
        assertThat(mergePage.getByText("Expenses")).isVisible()
        assertThat(page.getByText("Groceries will be merged into Food")).isVisible()
        page.getByText("Groceries will be merged into Food").scrollIntoViewIfNeeded()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Merge category")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Budget settings"))).isVisible()
        assertThat(page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Expense Categories"))).isVisible()
        expenseCategoryRepository.findByIdAndUserId(groceries.id!!, alice.id!!).shouldBe(null)
        transactionRepository.findById(expense.id!!).get().categoryId.shouldBe(food.id)
        page.shouldEventuallyContainCategoryRows(CategoryRow("Food", "Active", "archive edit"))
    }

    @Test
    fun hidesExpenseCategoryMergeActionWhenThereIsNoTarget(page: Page) {
        val alice = saveUser("alice")
        saveCategory(alice, "Groceries")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings?tab=expense-categories")

        page.shouldEventuallyContainCategoryRows(CategoryRow("Groceries", "Active", "archive edit"))
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Merge Groceries"))).not().isVisible()
    }

    @Test
    fun archivesAndUnarchivesCategoriesFromSettingsPage(page: Page) {
        val alice = saveUser("alice")
        val groceries = saveCategory(alice, "Groceries")
        saveCategory(alice, "Rent")
        val salary = saveIncomeCategory(alice, "Salary")
        saveIncomeCategory(alice, "Bonus")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings?tab=expense-categories")
        page.locator("[data-testid='expense-category-row-${groceries.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Archive Groceries"))
            .click()

        assertThat(page.getByRole(AriaRole.DIALOG).getByText("Archive category?")).isVisible()
        assertThat(page.getByText("Groceries will be hidden from transaction forms and filters.")).isVisible()
        page.getByRole(AriaRole.DIALOG)
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Archive category"))
            .click()

        page.shouldEventuallyContainCategoryRows(
            CategoryRow("Groceries", "Archived", "archive merge edit"),
            CategoryRow("Rent", "Active", "archive edit"),
        )
        expenseCategoryRepository.findById(groceries.id!!).get().archived.shouldBe(true)

        page.locator("[data-testid='expense-category-row-${groceries.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Unarchive Groceries"))
            .click()
        page.shouldEventuallyContainCategoryRows(
            CategoryRow("Groceries", "Active", "archive merge edit"),
            CategoryRow("Rent", "Active", "archive merge edit"),
        )
        expenseCategoryRepository.findById(groceries.id!!).get().archived.shouldBe(false)

        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Income Categories")).click()
        page.locator("[data-testid='income-category-row-${salary.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Archive Salary"))
            .click()
        page.getByRole(AriaRole.DIALOG)
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Archive category"))
            .click()

        page.shouldEventuallyContainIncomeCategoryRows(
            CategoryRow("Bonus", "Active", "archive edit"),
            CategoryRow("Salary", "Archived", "archive merge edit"),
        )
        incomeCategoryRepository.findById(salary.id!!).get().archived.shouldBe(true)
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
            CategoryRow("Interest", "Active", "archive merge edit"),
            CategoryRow("Salary", "Active", "archive merge edit"),
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
            CategoryRow("Bonus", "Active", "archive merge edit"),
            CategoryRow("Interest", "Active", "archive merge edit"),
            CategoryRow("Salary", "Active", "archive merge edit"),
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
            CategoryRow("Bonus", "Active", "archive merge edit"),
            CategoryRow("Interest", "Active", "archive merge edit"),
            CategoryRow("Payroll", "Active", "archive merge edit"),
        )
    }

    @Test
    fun mergesIncomeCategoryFromSettingsPage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        val salary = saveIncomeCategory(alice, "Salary")
        val payroll = saveIncomeCategory(alice, "Payroll")
        val income = saveTransaction(alice, main, salary, TransactionType.INCOME, 1_000)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings?tab=income-categories")
        page.locator("[data-testid='income-category-row-${salary.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Merge Salary"))
            .click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Merge Salary"))).isVisible()
        val mergePage = page.getByRole(AriaRole.MAIN)
        assertThat(mergePage.getByText("Income")).isVisible()
        assertThat(page.getByText("Salary will be merged into Payroll")).isVisible()
        page.getByText("Salary will be merged into Payroll").scrollIntoViewIfNeeded()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Merge category")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Budget settings"))).isVisible()
        assertThat(page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Income Categories"))).isVisible()
        incomeCategoryRepository.findByIdAndUserId(salary.id!!, alice.id!!).shouldBe(null)
        transactionRepository.findById(income.id!!).get().categoryId.shouldBe(payroll.id)
        page.shouldEventuallyContainIncomeCategoryRows(CategoryRow("Payroll", "Active", "archive edit"))
    }

    @Test
    fun importsToshlCsvFromSettingsPage(page: Page) {
        saveUser("alice")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        val csvFile = Files.createTempFile("toshl-import", ".csv")
        Files.writeString(
            csvFile,
            """
                Date,Account,Category,Tags,Expense amount,Income amount,Currency,In main currency,Main currency,Description
                1/6/26,Cash,Food,travel,12.34,0,AUD,12.34,AUD,Lunch
                2/6/26,Bank,Salary,,0,100.00,AUD,100.00,AUD,Pay
                3/6/26,Cash,Transfer,,50.00,0,AUD,50.00,AUD,
            """.trimIndent(),
        )

        page.navigate(server.url.toString() + "/settings?tab=import")

        assertThat(page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Import"))).isVisible()
        assertThat(
            page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Toshl").setExact(true)),
        ).isVisible()
        assertThat(page.getByText("Prepare your Toshl export")).isVisible()
        assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Open Exports and reports")))
            .hasAttribute("target", "_blank")
        page.locator("input[type='file']").setInputFiles(csvFile)
        assertThat(page.getByText(csvFile.fileName.toString())).isVisible()

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Import").setExact(true)).click()

        assertThat(
            page.getByRole(AriaRole.ALERT).filter(Locator.FilterOptions().setHasText("Import completed with warnings")),
        ).isVisible()
        assertThat(page.getByText("Imported 1 expenses and 1 income entries.")).isVisible()
        assertThat(page.getByText("1 transfer row could not be matched.")).isVisible()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Get processing report"))).isVisible()
        assertThat(page.getByText("Some transfers could not be matched")).not().isVisible()
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

    private fun saveTransaction(
        user: User,
        account: TrackingAccount,
        category: Any,
        type: TransactionType,
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
            date = LocalDate.parse("2026-06-01"),
            amountMinor = amountMinor,
        ),
    )

    private fun saveTransfer(
        user: User,
        sourceAccount: TrackingAccount,
        targetAccount: TrackingAccount,
        sourceAmountMinor: Long,
        targetAmountMinor: Long,
    ): FundsTransfer = fundsTransferRepository.save(
        FundsTransfer(
            userId = user.id!!,
            sourceAccountId = sourceAccount.id!!,
            targetAccountId = targetAccount.id!!,
            sourceAmountMinor = sourceAmountMinor,
            targetAmountMinor = targetAmountMinor,
            date = LocalDate.parse("2026-06-01"),
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
                status = cells.getOrElse(4) { "" },
                action = cells.getOrElse(5) { "" },
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
                status = cells.getOrElse(1) { "" },
                action = cells.getOrElse(2) { "" },
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
                status = cells.getOrElse(1) { "" },
                action = cells.getOrElse(2) { "" },
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
    val status: String,
    val action: String,
)

private data class CategoryRow(
    val name: String,
    val status: String,
    val action: String,
)
