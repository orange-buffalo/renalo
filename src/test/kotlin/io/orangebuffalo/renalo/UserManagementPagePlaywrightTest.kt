package io.orangebuffalo.renalo

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestAuthTokens
import io.orangebuffalo.renalo.test.shouldEventually
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class UserManagementPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun managesUsersFromAdminPage(page: Page) {
        saveUser("admin", "password", UserType.ADMIN)
        saveUser("alice", "password", UserType.USER)
        saveUser("bob", "password", UserType.USER)
        saveUser("charlie", "password", UserType.USER)
        saveUser("dana", "password", UserType.USER)
        saveUser("erin", "password", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("admin", UserType.ADMIN))

        page.navigate(server.url.toString() + "/user-management")

        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Users"))).isVisible()
        page.shouldEventuallyContainRows(
            UserRow("admin", "Admin", "Active", "edit"),
            UserRow("alice", "User", "Active", "edit trash"),
            UserRow("bob", "User", "Active", "edit trash"),
            UserRow("charlie", "User", "Active", "edit trash"),
            UserRow("dana", "User", "Active", "edit trash"),
        )
        assertThat(page.getByRole(AriaRole.NAVIGATION, Page.GetByRoleOptions().setName("Pagination Navigation"))).isVisible()

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Next")).click()
        page.shouldEventuallyContainRows(UserRow("erin", "User", "Active", "edit trash"))

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Previous")).click()
        page.shouldEventuallyContainRows(
            UserRow("admin", "Admin", "Active", "edit"),
            UserRow("alice", "User", "Active", "edit trash"),
            UserRow("bob", "User", "Active", "edit trash"),
            UserRow("charlie", "User", "Active", "edit trash"),
            UserRow("dana", "User", "Active", "edit trash"),
        )
        page.locator("[data-testid='user-row-${userRepository.findByUsername("alice")!!.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Edit alice"))
            .click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Edit alice"))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Back")).click()
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Users"))).isVisible()

        page.locator("[data-testid='user-row-${userRepository.findByUsername("alice")!!.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Remove alice"))
            .click()

        assertThat(page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Remove alice?"))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Cancel")).click()
        assertThat(page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Remove alice?"))).not().isVisible()
        assertThat(page.locator("[data-testid='remove-user-overlay']")).not().isVisible()
        assertThat(page.getByRole(AriaRole.GRID, Page.GetByRoleOptions().setName("Users"))).isVisible()

        page.locator("[data-testid='user-row-${userRepository.findByUsername("alice")!!.id}']")
            .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Remove alice"))
            .click()
        assertThat(page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Remove alice?"))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Remove user")).click()

        page.shouldEventuallyContainRows(
            UserRow("admin", "Admin", "Active", "edit"),
            UserRow("bob", "User", "Active", "edit trash"),
            UserRow("charlie", "User", "Active", "edit trash"),
            UserRow("dana", "User", "Active", "edit trash"),
            UserRow("erin", "User", "Active", "edit trash"),
        )
        assertThat(page.getByText("User removed.")).isVisible()
        assertThat(page.getByText("alice no longer has access to Renalo.")).isVisible()
        userRepository.findByUsername("alice").shouldBeNull()
    }

    private fun saveUser(username: String, password: String, type: UserType): User {
        return userRepository.save(User(username = username, passwordHash = passwordHasher.hash(password), type = type))
    }

    private fun extractUserRows(page: Page): List<UserRow> {
        @Suppress("UNCHECKED_CAST")
        val rows = page.locator("[data-testid^='user-row-']").evaluateAll(
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
            UserRow(
                username = cells.getOrElse(0) { "" },
                type = cells.getOrElse(1) { "" },
                active = cells.getOrElse(2) { "" },
                action = cells.getOrElse(3) { "" },
            )
        }
    }

    private fun Page.shouldEventuallyContainRows(vararg expectedRows: UserRow) {
        shouldEventually {
            extractUserRows(this).shouldContainExactly(*expectedRows)
        }
    }
}

private data class UserRow(
    val username: String,
    val type: String,
    val active: String,
    val action: String,
)
