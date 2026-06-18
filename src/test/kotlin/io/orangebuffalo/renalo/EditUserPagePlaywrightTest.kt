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
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class EditUserPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun updatesUsernameFromEditUserPage(page: Page) {
        saveUser("admin", "password", UserType.ADMIN)
        val alice = saveUser("alice", "password", UserType.USER, active = false)
        setStoredToken(page, testAuthTokens.issueToken("admin", UserType.ADMIN))

        page.navigate(server.url.toString() + "/user-management/${alice.id}")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Edit alice"))).isVisible()
        assertThat(page.locator("[data-testid='user-status-badge']")).containsText("Inactive")
        assertThat(page.getByLabel("Type")).hasValue("User")
        assertThat(page.getByLabel("Type")).isDisabled()
        assertThat(page.getByLabel("Username")).hasValue("alice")

        page.getByLabel("Username").fill("frank")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Save changes")).click()

        page.shouldEventually {
            userRepository.findByUsername("frank")?.active.shouldBe(false)
        }
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Edit frank"))).isVisible()
        assertThat(page.locator("[data-testid='user-status-badge']")).containsText("Inactive")
    }

    private fun saveUser(username: String, password: String, type: UserType, active: Boolean = true): User {
        return userRepository.save(User(username = username, passwordHash = passwordHasher.hash(password), type = type, active = active))
    }
}
