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
        page.getByRole(AriaRole.TAB, Page.GetByRoleOptions().setName("Expenses Categories")).click()
        assertThat(page.getByText("Expense categories will be configured here.")).isVisible()
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
        assertThat(page.getByText("Changing currency will implicitly change the currency")).isVisible()
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
    fun cancelsAccountCreateBackToSettings(page: Page) {
        val alice = saveUser("alice")
        saveAccount(alice, "Main", "AUD", 0, isDefault = true)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/settings/accounts/create")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Cancel")).click()

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
}

private data class AccountRow(
    val name: String,
    val currency: String,
    val initialBalance: String,
    val default: String,
    val action: String,
)
