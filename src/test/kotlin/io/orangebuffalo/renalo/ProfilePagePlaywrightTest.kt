package io.orangebuffalo.renalo

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.test.TestAuthTokens
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class ProfilePagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun changesPasswordFromProfilePage(page: Page) {
        saveUser("alice", "old-password", UserType.USER)

        page.navigate(server.url.toString() + "/")
        page.getByLabel("Username").fill("alice")
        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Password")).fill("old-password")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()

        page.navigate(server.url.toString() + "/profile")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("My profile"))).isVisible()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Change password"))).isVisible()

        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Current password").setExact(true))
            .fill("old-password")
        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("New password").setExact(true))
            .fill("new-password")
        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Confirm new password").setExact(true))
            .fill("new-password")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Change password")).click()

        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Password changed")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open account menu")).click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign out")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Sign in to Renalo"))).isVisible()

        page.getByLabel("Username").fill("alice")
        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Password")).fill("new-password")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
    }

    @Test
    fun showsValidationErrorsForPasswordChange(page: Page) {
        saveUser("alice", "old-password", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/profile")

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Change password")).click()
        assertThat(page.getByText("Enter your current password.")).isVisible()

        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Current password").setExact(true))
            .fill("wrong-password")
        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("New password").setExact(true))
            .fill("new-password")
        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Confirm new password").setExact(true))
            .fill("new-password")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Change password")).click()

        assertThat(page.getByText("Current password is incorrect.")).isVisible()
    }

    private fun saveUser(username: String, password: String, type: UserType): User {
        return userRepository.save(User(username = username, passwordHash = passwordHasher.hash(password), type = type))
    }
}
