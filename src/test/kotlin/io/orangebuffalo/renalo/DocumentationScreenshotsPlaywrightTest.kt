package io.orangebuffalo.renalo

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.ScreenshotAnimations
import com.microsoft.playwright.options.ScreenshotCaret
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.recurrence.RecurrenceInterval
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestAuthTokens
import io.orangebuffalo.renalo.test.TestTimeProvider
import io.orangebuffalo.renalo.tracking.AccountAdjustment
import io.orangebuffalo.renalo.tracking.AccountAdjustmentRepository
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.FundsTransfer
import io.orangebuffalo.renalo.tracking.FundsTransferRepository
import io.orangebuffalo.renalo.tracking.IncomeCategory
import io.orangebuffalo.renalo.tracking.IncomeCategoryRepository
import io.orangebuffalo.renalo.tracking.RecurringTransactionRule
import io.orangebuffalo.renalo.tracking.RecurringTransactionRuleRepository
import io.orangebuffalo.renalo.tracking.RecurringTransactionRuleStatus
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.tracking.Transaction
import io.orangebuffalo.renalo.tracking.TransactionRepository
import io.orangebuffalo.renalo.tracking.TransactionType
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserActivationToken
import io.orangebuffalo.renalo.user.UserActivationTokenRepository
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.Comparator

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
@EnabledIfEnvironmentVariable(named = "RENALO_DOCUMENTATION_SCREENSHOTS", matches = "(?i:true|1)")
class DocumentationScreenshotsPlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Inject
    lateinit var userActivationTokenRepository: UserActivationTokenRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var expenseCategoryRepository: ExpenseCategoryRepository

    @Inject
    lateinit var incomeCategoryRepository: IncomeCategoryRepository

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var recurringTransactionRuleRepository: RecurringTransactionRuleRepository

    @Inject
    lateinit var fundsTransferRepository: FundsTransferRepository

    @Inject
    lateinit var accountAdjustmentRepository: AccountAdjustmentRepository

    @Test
    fun capturesApplicationFeatureTour(page: Page) {
        resetScreenshotDirectory()
        val fixture = createGuitaristBudgetFixture()

        DocumentationLayout.entries.forEach { layout ->
            page.setViewportSize(layout.width, layout.height)
            captureFeatureTour(page, fixture, layout)
        }
    }

    private fun captureFeatureTour(page: Page, fixture: DocumentationFixture, layout: DocumentationLayout) {
        clearAuthentication(page)
        page.navigate(server.url.toString() + "/")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Sign in to Renalo"))).isVisible()
        capture(page, layout, "01-login")

        page.navigate(server.url.toString() + "/activate-account?token=documentation-activation-token")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Set your password"))).isVisible()
        capture(page, layout, "02-account-activation")

        authenticate(page, testAuthTokens.issueToken(fixture.musician.username, UserType.USER))
        page.navigate(server.url.toString() + "/tracking")
        assertThat(page.locator("[data-testid='dashboard-account-card']").first()).isVisible()
        capture(page, layout, "03-dashboard")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record new").setExact(true)).click()
        assertThat(page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Expense"))).isVisible()
        capture(page, layout, "04-dashboard-record-new")

        page.navigate(server.url.toString() + "/expenses")
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
        if (layout == DocumentationLayout.MOBILE) {
            page.locator("[data-testid^='expense-row-']").first().click()
        }
        capture(page, layout, "05-expenses-overview")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("More filters")).click()
        assertThat(page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("More filters"))).isVisible()
        capture(page, layout, "06-expense-filters")

        page.navigate(server.url.toString() + "/expenses/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add expense"))).isVisible()
        selectDropdown(page, "Category", "Guitar gear")
        page.locator("input[name='amount']").fill("89.00")
        page.getByLabel("Notes").fill("Fresh strings and a setup before Friday's show")
        page.locator(".transaction-recurring-checkbox").click()
        assertThat(page.getByLabel("Repeat")).isVisible()
        if (layout == DocumentationLayout.MOBILE) {
            page.locator(".transaction-recurrence-section").scrollIntoViewIfNeeded()
        }
        capture(page, layout, "07-expense-recurring-form")

        page.navigate(server.url.toString() + "/incomes")
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Incomes"))).isVisible()
        if (layout == DocumentationLayout.MOBILE) {
            page.locator("[data-testid^='income-row-']").first().click()
        }
        capture(page, layout, "08-incomes-overview")

        page.navigate(server.url.toString() + "/incomes/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add income"))).isVisible()
        selectDropdown(page, "Income category", "Gig fees")
        page.locator("input[name='amount']").fill("950.00")
        page.getByLabel("Notes").fill("Saturday night headline set at The Voltage Room")
        capture(page, layout, "09-income-form")

        page.navigate(server.url.toString() + "/transfers")
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Transfers"))).isVisible()
        if (layout == DocumentationLayout.MOBILE) {
            page.locator("[data-testid^='funds-transfer-row-']").first().click()
        }
        capture(page, layout, "10-transfers-overview")

        page.navigate(server.url.toString() + "/transfers/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add transfer"))).isVisible()
        selectDropdown(page, "Source account", "Everyday")
        selectDropdown(page, "Target account", "European tour")
        page.locator("input[name='sourceAmount']").fill("350.00")
        page.locator("input[name='targetAmount']").fill("210.00")
        assertThat(page.locator("input[name='exchangeRate']")).isVisible()
        capture(page, layout, "11-transfer-cross-currency-form")

        page.navigate(server.url.toString() + "/settings")
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Tracking accounts"))).isVisible()
        capture(page, layout, "12-settings-accounts")

        page.navigate(server.url.toString() + "/settings?tab=expense-categories")
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Expense categories"))).isVisible()
        capture(page, layout, "13-settings-expense-categories")

        page.navigate(server.url.toString() + "/settings?tab=income-categories")
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Income categories"))).isVisible()
        capture(page, layout, "14-settings-income-categories")

        page.navigate(server.url.toString() + "/settings?tab=import")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Toshl").setExact(true))).isVisible()
        capture(page, layout, "15-settings-toshl-import")

        page.navigate(server.url.toString() + "/settings/accounts/${fixture.tourFund.id}/adjustments")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Adjustments — Tour fund"))).isVisible()
        capture(page, layout, "16-account-adjustments")

        page.navigate(server.url.toString() + "/settings/accounts/${fixture.tourFund.id}/merge")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Merge Tour fund"))).isVisible()
        capture(page, layout, "17-account-merge")

        page.navigate(server.url.toString() + "/settings/expense-categories/${fixture.guitarGear.id}/merge")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Merge Guitar gear"))).isVisible()
        capture(page, layout, "18-category-merge")

        page.navigate(server.url.toString() + "/profile")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("My profile"))).isVisible()
        capture(page, layout, "19-profile-security")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create link")).click()
        assertThat(page.getByAltText("Sign in link QR code")).isVisible()
        page.getByLabel("Sign in link").scrollIntoViewIfNeeded()
        capture(page, layout, "20-profile-sign-in-link")

        authenticate(page, testAuthTokens.issueToken(fixture.admin.username, UserType.ADMIN))
        page.navigate(server.url.toString() + "/user-management")
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Users"))).isVisible()
        capture(page, layout, "21-user-management")

        page.navigate(server.url.toString() + "/user-management/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Create user"))).isVisible()
        page.getByLabel("Username").fill("tour.manager")
        capture(page, layout, "22-create-user")

        page.navigate(server.url.toString() + "/user-management/${fixture.inactiveDrummer.id}")
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Activation required")
        capture(page, layout, "23-edit-inactive-user")
    }

    private fun createGuitaristBudgetFixture(): DocumentationFixture {
        val musician = saveUser("maya.riff", UserType.USER)
        val admin = saveUser("admin", UserType.ADMIN)
        saveUser("amp.tech", UserType.USER)
        saveUser("bassline", UserType.USER)
        val inactiveDrummer = saveUser("drummer", UserType.USER, active = false)
        saveUser("keys.and.synths", UserType.USER)
        saveUser("sound.engineer", UserType.USER, active = false)
        userActivationTokenRepository.save(
            UserActivationToken(
                userId = inactiveDrummer.id!!,
                token = "documentation-activation-token",
                expiresAt = Instant.parse("2099-06-15T08:00:00Z"),
            ),
        )

        val everyday = saveAccount(musician, "Everyday", "AUD", 125_000, isDefault = true)
        val tourFund = saveAccount(musician, "Tour fund", "AUD", 250_000)
        val europeanTour = saveAccount(musician, "European tour", "EUR", 80_000)
        saveAccount(musician, "Cash tin", "AUD", 15_000, archived = true)

        val guitarGear = saveExpenseCategory(musician, "Guitar gear")
        val studioHire = saveExpenseCategory(musician, "Studio hire")
        val travel = saveExpenseCategory(musician, "Tour travel")
        val promotion = saveExpenseCategory(musician, "Promotion")
        val software = saveExpenseCategory(musician, "Music software")
        saveExpenseCategory(musician, "Old rehearsal costs", archived = true)

        val gigFees = saveIncomeCategory(musician, "Gig fees")
        val teaching = saveIncomeCategory(musician, "Guitar lessons")
        val royalties = saveIncomeCategory(musician, "Royalties")
        val merch = saveIncomeCategory(musician, "Merch sales")
        saveIncomeCategory(musician, "Old sponsorship", archived = true)

        saveTransaction(musician, everyday, gigFees, TransactionType.INCOME, today, 75_000, "Friday headline set — The Voltage Room")
        saveTransaction(musician, everyday, teaching, TransactionType.INCOME, today.minusDays(2), 32_000, "Four private guitar lessons")
        saveTransaction(musician, everyday, royalties, TransactionType.INCOME, today.minusDays(8), 18_345, "Streaming and radio royalties")
        saveTransaction(musician, tourFund, merch, TransactionType.INCOME, today.plusDays(14), 42_500, "Limited-edition tour shirts")

        saveTransaction(musician, everyday, guitarGear, TransactionType.EXPENSE, today, 2_899, "Strings, picks, and a quick setup")
        saveTransaction(musician, everyday, studioHire, TransactionType.EXPENSE, today.minusDays(1), 8_500, "Evening rehearsal with the full band")
        saveTransaction(musician, tourFund, travel, TransactionType.EXPENSE, today.minusDays(4), 12_900, "Van fuel for the coastal run")
        saveTransaction(musician, everyday, promotion, TransactionType.EXPENSE, today.minusDays(6), 4_500, "Poster printing and promoted posts")

        val softwareRule = recurringTransactionRuleRepository.save(
            RecurringTransactionRule(
                userId = musician.id!!,
                transactionType = TransactionType.EXPENSE,
                trackingAccountId = everyday.id!!,
                categoryId = software.id!!,
                startDate = today,
                endDate = today.plusMonths(3),
                recurrenceFrequency = 1,
                recurrenceInterval = RecurrenceInterval.MONTH,
                status = RecurringTransactionRuleStatus.ACTIVE,
                generatedUntil = today.plusMonths(1),
                amountMinor = 2_999,
                notes = "Amp simulator subscription",
            ),
        )
        saveTransaction(
            musician,
            everyday,
            software,
            TransactionType.EXPENSE,
            today,
            2_999,
            "Amp simulator subscription",
            softwareRule,
        )
        saveTransaction(
            musician,
            everyday,
            software,
            TransactionType.EXPENSE,
            today.plusMonths(1),
            2_999,
            "Amp simulator subscription",
            softwareRule,
        )

        fundsTransferRepository.save(
            FundsTransfer(
                userId = musician.id!!,
                sourceAccountId = everyday.id!!,
                targetAccountId = tourFund.id!!,
                sourceAmountMinor = 30_000,
                targetAmountMinor = 30_000,
                date = today.minusDays(3),
            ),
        )
        fundsTransferRepository.save(
            FundsTransfer(
                userId = musician.id!!,
                sourceAccountId = everyday.id!!,
                targetAccountId = europeanTour.id!!,
                sourceAmountMinor = 35_000,
                targetAmountMinor = 21_000,
                date = today.minusDays(5),
            ),
        )
        accountAdjustmentRepository.save(
            AccountAdjustment(
                userId = musician.id!!,
                trackingAccountId = tourFund.id!!,
                adjustmentAmountMinor = 12_500,
                date = today.minusDays(7),
            ),
        )
        accountAdjustmentRepository.save(
            AccountAdjustment(
                userId = musician.id!!,
                trackingAccountId = tourFund.id!!,
                adjustmentAmountMinor = -4_000,
                date = today.minusDays(2),
            ),
        )

        return DocumentationFixture(
            musician = musician,
            admin = admin,
            inactiveDrummer = inactiveDrummer,
            tourFund = tourFund,
            guitarGear = guitarGear,
        )
    }

    private fun saveUser(username: String, type: UserType, active: Boolean = true): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("stage-door-password"),
            type = type,
            active = active,
        ),
    )

    private fun saveAccount(
        user: User,
        name: String,
        currency: String,
        initialBalanceMinor: Long,
        isDefault: Boolean = false,
        archived: Boolean = false,
    ): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = currency,
            initialBalanceMinor = initialBalanceMinor,
            isDefault = isDefault,
            archived = archived,
        ),
    )

    private fun saveExpenseCategory(user: User, name: String, archived: Boolean = false): ExpenseCategory =
        expenseCategoryRepository.save(ExpenseCategory(userId = user.id!!, name = name, archived = archived))

    private fun saveIncomeCategory(user: User, name: String, archived: Boolean = false): IncomeCategory =
        incomeCategoryRepository.save(IncomeCategory(userId = user.id!!, name = name, archived = archived))

    private fun saveTransaction(
        user: User,
        account: TrackingAccount,
        category: Any,
        type: TransactionType,
        date: LocalDate,
        amountMinor: Long,
        notes: String,
        recurringRule: RecurringTransactionRule? = null,
    ): Transaction = transactionRepository.save(
        Transaction(
            userId = user.id!!,
            type = type,
            trackingAccountId = account.id!!,
            categoryId = when (category) {
                is ExpenseCategory -> category.id!!
                is IncomeCategory -> category.id!!
                else -> error("Unsupported documentation category")
            },
            date = date,
            amountMinor = amountMinor,
            notes = notes,
            recurringRuleId = recurringRule?.id,
            recurringInstanceDate = recurringRule?.let { date },
        ),
    )

    private fun selectDropdown(page: Page, label: String, option: String) {
        page.getByLabel(label).click()
        page.locator(".searchable-dropdown-popover")
            .getByText(option, Locator.GetByTextOptions().setExact(true))
            .click()
    }

    private fun clearAuthentication(page: Page) {
        if (page.url().startsWith(server.url.toString())) {
            page.evaluate("window.localStorage.clear(); window.sessionStorage.clear();")
        }
    }

    private fun authenticate(page: Page, token: String) {
        page.evaluate(
            """
                token => {
                    window.localStorage.setItem('renalo.authToken', token);
                    window.sessionStorage.setItem('renalo.testStoredTokenSeeded', 'true');
                }
            """.trimIndent(),
            token,
        )
    }

    private fun capture(page: Page, layout: DocumentationLayout, name: String) {
        page.locator("body").waitFor()
        page.evaluate("document.fonts.ready")
        Files.createDirectories(screenshotRoot.resolve(layout.directory))
        page.screenshot(
            Page.ScreenshotOptions()
                .setPath(screenshotRoot.resolve(layout.directory).resolve("$name.png"))
                .setAnimations(ScreenshotAnimations.DISABLED)
                .setCaret(ScreenshotCaret.HIDE),
        )
    }

    private fun resetScreenshotDirectory() {
        if (Files.exists(screenshotRoot)) {
            Files.walk(screenshotRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
        Files.createDirectories(screenshotRoot)
    }

    private val today = TestTimeProvider.DEFAULT_DATE
    private val screenshotRoot: Path = Path.of("build", "documentation-screenshots")
}

private enum class DocumentationLayout(
    val directory: String,
    val width: Int,
    val height: Int,
) {
    DESKTOP("desktop", 1100, 875),
    MOBILE("mobile", 390, 844),
}

private data class DocumentationFixture(
    val musician: User,
    val admin: User,
    val inactiveDrummer: User,
    val tourFund: TrackingAccount,
    val guitarGear: ExpenseCategory,
)
