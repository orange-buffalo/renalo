package io.orangebuffalo.renalo

import com.microsoft.playwright.Page
import com.microsoft.playwright.Locator
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestAuthTokens
import io.orangebuffalo.renalo.test.shouldEventually
import io.orangebuffalo.renalo.tracking.AccountAdjustmentRepository
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
class AccountAdjustmentsPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var trackingAccountRepository: TrackingAccountRepository

    @Inject
    lateinit var accountAdjustmentRepository: AccountAdjustmentRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun showsEmptyAdjustmentsPageForAccount(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", 10000, isDefault = true)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings/accounts/${main.id}/adjustments")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Adjustments — Main"))).isVisible()
        assertThat(page.getByText("Balance: A$100.00")).isVisible()
        assertThat(page.getByText("No adjustments yet")).isVisible()
    }

    @Test
    fun createsAndDeletesAdjustmentsFromAdjustmentsPage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", 10000, isDefault = true)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings/accounts/${main.id}/adjustments")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Adjustments — Main"))).isVisible()
        assertThat(page.getByText("Balance: A$100.00")).isVisible()

        page.locator("input[name='adjustmentAmount']").fill("50")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add adjustment")).click()

        page.shouldEventuallyContainAdjustmentRowsInAnyOrder(
            AdjustmentRow("A$50.00"),
        )
        assertThat(page.getByText("Balance: A$150.00")).isVisible()
        accountAdjustmentRepository.findByUserId(alice.id!!).size.shouldBe(1)
        accountAdjustmentRepository.findByUserId(alice.id!!).first().adjustmentAmountMinor.shouldBe(5000)

        page.locator("input[name='adjustmentAmount']").fill("-25.50")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add adjustment")).click()

        page.shouldEventuallyContainAdjustmentRowsInAnyOrder(
            AdjustmentRow("A$50.00"),
            AdjustmentRow("-A$25.50"),
        )
        assertThat(page.getByText("Balance: A$124.50")).isVisible()
        accountAdjustmentRepository.findByUserId(alice.id!!).size.shouldBe(2)

        page.locator("input[name='targetBalance']").fill("200")
        page.locator("input[name='adjustmentAmount']").inputValue().shouldBe("75.50")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add adjustment")).click()
        assertThat(page.getByText("Balance: A$200.00")).isVisible()

        page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName("Delete adjustment of A\$75.50"),
        ).click()
        page.getByRole(AriaRole.DIALOG).getByRole(
            AriaRole.BUTTON,
            Locator.GetByRoleOptions().setName("Delete adjustment"),
        ).click()
        assertThat(page.getByText("Balance: A$124.50")).isVisible()
        accountAdjustmentRepository.findByUserId(alice.id!!).size.shouldBe(2)
    }

    @Test
    fun rejectsZeroAdjustment(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", 10000, isDefault = true)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings/accounts/${main.id}/adjustments")

        page.locator("input[name='adjustmentAmount']").fill("0")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Add adjustment")).click()

        assertThat(page.getByText("Enter a valid non-zero amount.")).isVisible()
        accountAdjustmentRepository.findByUserId(alice.id!!).shouldBe(emptyList())
    }

    @Test
    fun navigatesBackToSettingsFromAdjustmentsPage(page: Page) {
        val alice = saveUser("alice")
        val main = saveAccount(alice, "Main", "AUD", 10000, isDefault = true)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings/accounts/${main.id}/adjustments")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Back to settings")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Budget settings"))).isVisible()
        assertThat(page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Accounts"))).isVisible()
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

    private fun extractAdjustmentRows(page: Page): List<AdjustmentRow> {
        @Suppress("UNCHECKED_CAST")
        val rows = page.locator("[data-testid^='adjustment-row-']").evaluateAll(
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
            AdjustmentRow(
                amount = cells.getOrElse(0) { "" },
            )
        }
    }

    private fun Page.shouldEventuallyContainAdjustmentRowsInAnyOrder(vararg expectedRows: AdjustmentRow) {
        shouldEventually {
            extractAdjustmentRows(this).shouldContainExactlyInAnyOrder(*expectedRows)
        }
    }
}

private data class AdjustmentRow(
    val amount: String,
)
