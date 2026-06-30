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
        page.locator("input[name='sourceAmount']").fill("50")
        page.locator("input[name='targetAmount']").fill("31")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save transfer")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Transfers"))).isVisible()
        val updatedTransfer = fundsTransferRepository.findById(sameCurrencyTransfer.id!!).get()
        updatedTransfer.targetAccountId.shouldBe(travel.id)
        updatedTransfer.sourceAmountMinor.shouldBe(5000)
        updatedTransfer.targetAmountMinor.shouldBe(3100)
        page.shouldEventuallyContainTransferRows(
            TransferRow("Main -> Travel", "A$50.00 → €31.00", "Today", "edit delete"),
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
            TransferRow("Main -> Travel", "A$50.00 → €31.00", "Today", "edit delete"),
        )
        fundsTransferRepository.findById(existingTransfer.id!!).isPresent.shouldBe(false)

        page.setViewportSize(390, 844)
        val transferRow = page.locator("[data-testid='funds-transfer-row-${sameCurrencyTransfer.id}']")
        assertThat(transferRow.getByText("Main -> Travel")).isVisible()
        assertThat(transferRow.getByText("Today")).isVisible()
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

    private fun selectOption(page: Page, label: String, option: String) {
        page.getByLabel(label).click()
        page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName(option).setExact(true)).click()
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
