package io.orangebuffalo.renalo

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestAuthTokens
import io.orangebuffalo.renalo.test.TestTimeProvider
import io.orangebuffalo.renalo.test.shouldEventually
import io.orangebuffalo.renalo.tracking.FundsTransfer
import io.orangebuffalo.renalo.tracking.FundsTransferRepository
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
class FundsTransfersPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var fundsTransferRepository: FundsTransferRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun managesFundsTransfersFromTransfersPage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val savings = saveAccount(alice, "Savings", "AUD", isDefault = false)
        val travel = saveAccount(alice, "Travel", "EUR", isDefault = false)
        val existingTransfer = saveTransfer(
            alice,
            main,
            travel,
            TestTimeProvider.DEFAULT_DATE.minusDays(1),
            sourceAmountMinor = 15000,
            targetAmountMinor = 9200,
        )
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/transfers")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Transfers"))).isVisible()
        page.shouldEventuallyContainTransferRows(
            TransferRow("Main -> Travel", "A$150.00 → €92.00", "Yesterday", "edit delete"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add transfer")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add transfer"))).isVisible()
        selectOption(page, "Source account", "Main")
        selectOption(page, "Target account", "Savings")
        assertThat(page.getByLabel("Amount")).isVisible()
        assertThat(page.getByLabel("Source amount")).not().isVisible()
        assertThat(page.getByLabel("Target amount")).not().isVisible()
        page.locator("input[name='sourceAmount']").fill("42")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create transfer")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Transfers"))).isVisible()
        val sameCurrencyTransfer = fundsTransferRepository.findByUserIdOrderByDateDesc(alice.id!!)
            .first { it.sourceAccountId == main.id && it.targetAccountId == savings.id }
        sameCurrencyTransfer.sourceAmountMinor.shouldBe(4200)
        sameCurrencyTransfer.targetAmountMinor.shouldBe(4200)
        page.shouldEventuallyContainTransferRows(
            TransferRow("Main -> Savings", "A$42.00", "Today", "edit delete"),
            TransferRow("Main -> Travel", "A$150.00 → €92.00", "Yesterday", "edit delete"),
        )

        page.locator("[data-testid='funds-transfer-row-${sameCurrencyTransfer.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Edit Main to Savings transfer"))
            .click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Edit transfer"))).isVisible()
        selectOption(page, "Target account", "Travel")
        assertThat(page.getByLabel("Source amount")).isVisible()
        assertThat(page.getByLabel("Target amount")).isVisible()
        assertThat(page.getByLabel("Exchange rate")).isVisible()
        page.locator("input[name='sourceAmount']").fill("50")
        page.locator("input[name='targetAmount']").fill("31")
        assertThat(page.locator("input[name='exchangeRate']")).hasValue("0.62")
        page.locator("input[name='sourceAmount']").fill("100")
        assertThat(page.locator("input[name='exchangeRate']")).hasValue("0.31")
        page.locator("input[name='exchangeRate']").fill("0.5")
        assertThat(page.locator("input[name='targetAmount']")).hasValue("50.00")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save transfer")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Transfers"))).isVisible()
        val updatedTransfer = fundsTransferRepository.findById(sameCurrencyTransfer.id!!).get()
        updatedTransfer.targetAccountId.shouldBe(travel.id)
        updatedTransfer.sourceAmountMinor.shouldBe(10000)
        updatedTransfer.targetAmountMinor.shouldBe(5000)
        page.shouldEventuallyContainTransferRows(
            TransferRow("Main -> Travel", "A$100.00 → €50.00", "Today", "edit delete"),
            TransferRow("Main -> Travel", "A$150.00 → €92.00", "Yesterday", "edit delete"),
        )

        page.locator("[data-testid='funds-transfer-row-${existingTransfer.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Delete Main to Travel transfer"))
            .click()
        assertThat(
            page.getByRole(
                AriaRole.DIALOG,
                Page.GetByRoleOptions().setName("Delete transfer from Main to Travel?"),
            ),
        ).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Delete transfer")).click()

        page.shouldEventuallyContainTransferRows(
            TransferRow("Main -> Travel", "A$100.00 → €50.00", "Today", "edit delete"),
        )
        fundsTransferRepository.findById(existingTransfer.id!!).isPresent.shouldBe(false)

        page.setViewportSize(390, 844)
        val transferRow = page.locator("[data-testid='funds-transfer-row-${sameCurrencyTransfer.id}']")
        assertThat(transferRow.getByText("Main -> Travel")).isVisible()
        assertThat(transferRow.getByText("Today")).isVisible()
    }

    @Test
    fun validatesFundsTransferRequiredFields(page: Page) {
        val alice = saveUser("alice")
        saveAccount(alice, "Main", "AUD", isDefault = true)
        saveAccount(alice, "Savings", "AUD", isDefault = false)
        saveAccount(alice, "Travel", "EUR", isDefault = false)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/transfers/create")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add transfer"))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create transfer")).click()

        assertThat(page.getByText("Enter a valid amount greater than zero.")).isVisible()
        assertRequiredLabel(page, "Amount")

        selectOption(page, "Target account", "Travel")
        page.locator("input[name='sourceAmount']").fill("10")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create transfer")).click()

        assertThat(page.getByText("Enter a valid target amount greater than zero.")).isVisible()
        assertThat(page.getByText("Enter a valid exchange rate greater than zero.")).isVisible()
        assertRequiredLabel(page, "Source amount")
        assertRequiredLabel(page, "Target amount")
        assertRequiredLabel(page, "Exchange rate")
    }

    @Test
    fun filtersFundsTransfersBySharedDateRange(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val savings = saveAccount(alice, "Savings", "AUD", isDefault = false)
        val travel = saveAccount(alice, "Travel", "EUR", isDefault = false)
        saveTransfer(alice, main, savings, TestTimeProvider.DEFAULT_DATE.minusMonths(1), 1500, 1500)
        saveTransfer(alice, main, travel, TestTimeProvider.DEFAULT_DATE, 3000, 1800)
        saveTransfer(alice, savings, main, TestTimeProvider.DEFAULT_DATE.plusMonths(1), 7000, 7000)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/transfers")

        assertDateFilterLabel(page, "June 2099")
        page.shouldEventuallyContainTransferRows(
            TransferRow("Main -> Travel", "A$30.00 → €18.00", "Today", "edit delete"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Previous date range")).click()
        assertDateFilterLabel(page, "May 2099")
        page.shouldEventuallyContainTransferRows(
            TransferRow("Main -> Savings", "A$15.00", "May 14", "edit delete"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Next date range")).click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Next date range")).click()
        assertDateFilterLabel(page, "July 2099")
        page.shouldEventuallyContainTransferRows(
            TransferRow("Savings -> Main", "A$70.00", "Jul 14", "edit delete"),
        )

        applyDateFilterPreset(page, "July 2099", "All time")
        assertDateFilterLabel(page, "All time")
        page.shouldEventuallyContainTransferRows(
            TransferRow("Savings -> Main", "A$70.00", "Jul 14", "edit delete"),
            TransferRow("Main -> Travel", "A$30.00 → €18.00", "Today", "edit delete"),
            TransferRow("Main -> Savings", "A$15.00", "May 14", "edit delete"),
        )

        page.reload()
        assertDateFilterLabel(page, "June 2099")
        page.shouldEventuallyContainTransferRows(
            TransferRow("Main -> Travel", "A$30.00 → €18.00", "Today", "edit delete"),
        )
    }

    @Test
    fun filtersFundsTransfersBySourceAndTargetAccounts(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val savings = saveAccount(alice, "Savings", "AUD", isDefault = false)
        val travel = saveAccount(alice, "Travel", "EUR", isDefault = false)
        saveTransfer(alice, main, travel, TestTimeProvider.DEFAULT_DATE, 3000, 1800)
        saveTransfer(alice, main, savings, TestTimeProvider.DEFAULT_DATE, 1500, 1500)
        saveTransfer(alice, savings, travel, TestTimeProvider.DEFAULT_DATE, 2000, 1200)
        saveTransfer(alice, travel, main, TestTimeProvider.DEFAULT_DATE, 1800, 3000)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/transfers")

        page.shouldEventuallyContainTransferRows(
            TransferRow("Main -> Travel", "A$30.00 → €18.00", "Today", "edit delete"),
            TransferRow("Main -> Savings", "A$15.00", "Today", "edit delete"),
            TransferRow("Savings -> Travel", "A$20.00 → €12.00", "Today", "edit delete"),
            TransferRow("Travel -> Main", "€18.00 → A$30.00", "Today", "edit delete"),
        )

        openMoreFilters(page)
        selectMoreFilterOption(page, "Source account", "Main")
        selectMoreFilterOption(page, "Target account", "Travel")

        assertThat(page.locator(".transaction-filter-count-badge")).hasText("2")
        assertThat(page.getByLabel("Selected source account").getByText("Main")).isVisible()
        assertThat(page.getByLabel("Selected target account").getByText("Travel")).isVisible()
        page.shouldEventuallyContainTransferRows(
            TransferRow("Main -> Travel", "A$30.00 → €18.00", "Today", "edit delete"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Clear all")).click()

        assertThat(page.locator(".transaction-filter-count-badge")).not().isVisible()
        page.shouldEventuallyContainTransferRows(
            TransferRow("Main -> Travel", "A$30.00 → €18.00", "Today", "edit delete"),
            TransferRow("Main -> Savings", "A$15.00", "Today", "edit delete"),
            TransferRow("Savings -> Travel", "A$20.00 → €12.00", "Today", "edit delete"),
            TransferRow("Travel -> Main", "€18.00 → A$30.00", "Today", "edit delete"),
        )
    }

    private fun saveUser(username: String, type: UserType = UserType.USER): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = type,
            active = true,
        ),
    )

    private fun saveAccount(
        user: User,
        name: String,
        currency: String,
        isDefault: Boolean,
    ): TrackingAccount = trackingAccountRepository.save(
        TrackingAccount(
            userId = user.id!!,
            name = name,
            currency = currency,
            initialBalanceMinor = 0,
            isDefault = isDefault,
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

    @Test
    fun persistsSelectedTransferSourceAndTargetAccountsBetweenNavigations(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", isDefault = true)
        val savings = saveAccount(alice, "Savings", "AUD", isDefault = false)
        val travel = saveAccount(alice, "Travel", "EUR", isDefault = false)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/transfers/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add transfer"))).isVisible()
        assertThat(page.getByLabel("Source account")).containsText("Main")
        assertThat(page.getByLabel("Target account")).containsText("Savings")

        page.getByLabel("Source account").click()
        dropdownOption(page, "Travel").click()
        page.getByLabel("Target account").click()
        dropdownOption(page, "Main").click()
        assertThat(page.getByLabel("Source account")).containsText("Travel")
        assertThat(page.getByLabel("Target account")).containsText("Main")

        page.navigate(server.url.toString() + "/transfers")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Transfers"))).isVisible()

        page.navigate(server.url.toString() + "/transfers/create")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Add transfer"))).isVisible()
        assertThat(page.getByLabel("Source account")).containsText("Travel")
        assertThat(page.getByLabel("Target account")).containsText("Main")
    }

    private fun selectOption(page: Page, label: String, option: String) {
        page.getByLabel(label).click()
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

    private fun extractTransferRows(page: Page): List<TransferRow> {
        @Suppress("UNCHECKED_CAST")
        val rows = page.locator("[data-testid^='funds-transfer-row-']").evaluateAll(
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
            TransferRow(
                accounts = cells.getOrElse(0) { "" },
                amount = cells.getOrElse(1) { "" },
                date = cells.getOrElse(2) { "" },
                action = cells.getOrElse(3) { "" },
            )
        }
    }

    private fun Page.shouldEventuallyContainTransferRows(vararg expectedRows: TransferRow) {
        shouldEventually {
            extractTransferRows(this).shouldContainExactlyInAnyOrder(*expectedRows)
        }
    }

    private data class TransferRow(
        val accounts: String,
        val amount: String,
        val date: String,
        val action: String,
    )
}
