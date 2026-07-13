package io.orangebuffalo.renalo

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

    private fun saveUser(username: String): User =
        userRepository.save(User(username = username, passwordHash = passwordHasher.hash("password"), type = UserType.USER))

    private fun saveAccount(user: User, name: String, initialBalanceMinor: Long, isDefault: Boolean = false): TrackingAccount =
        trackingAccountRepository.save(
            TrackingAccount(
                userId = user.id!!,
                name = name,
                currency = "AUD",
                initialBalanceMinor = initialBalanceMinor,
                isDefault = isDefault,
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
}

private data class DashboardCardText(val text: String)
