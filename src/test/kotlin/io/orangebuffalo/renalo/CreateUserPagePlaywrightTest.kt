package io.orangebuffalo.renalo

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
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
import java.util.regex.Pattern
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class CreateUserPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun createsUserFromCreateUserPage(page: Page) {
        saveUser("admin", "password", UserType.ADMIN)
        saveUser("alice", "password", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("admin", UserType.ADMIN))

        page.navigate(server.url.toString() + "/user-management/create")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Create user"))).isVisible()

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create user")).click()
        assertThat(page.getByText("Enter a username.")).isVisible()

        page.getByLabel("Username").fill("alice")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create user")).click()
        assertThat(page.getByText("A user with this username already exists.")).isVisible()

        page.getByLabel("Username").fill("frank")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Create user")).click()

        page.shouldEventually {
            userRepository.findByUsername("frank")?.active.shouldBe(false)
        }
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Edit frank"))).isVisible()
        assertThat(page.locator("[data-testid='user-status-badge']")).containsText("Inactive")
        assertThat(page.getByText("User created. Share the activation link to finish setup.")).isVisible()
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Activation required")
        assertThat(page.getByLabel("Activation link")).hasValue(Pattern.compile(".*activate-account\\?token=.*"))
    }

    private fun saveUser(username: String, password: String, type: UserType): User {
        return userRepository.save(User(username = username, passwordHash = passwordHasher.hash(password), type = type))
    }
}
