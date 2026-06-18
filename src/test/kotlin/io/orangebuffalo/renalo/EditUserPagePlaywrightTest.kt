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
import io.orangebuffalo.renalo.user.UserActivationToken
import io.orangebuffalo.renalo.user.UserActivationTokenRepository
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import java.time.Instant
import java.util.regex.Pattern
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class EditUserPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var userActivationTokenRepository: UserActivationTokenRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun updatesUsernameFromEditUserPage(page: Page) {
        saveUser("admin", "password", UserType.ADMIN)
        val alice = saveUser("alice", "password", UserType.USER, active = false)
        saveActivationToken(alice, "activation-token")
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
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("User management"))).isVisible()
        assertThat(page.getByText("User changes saved.")).isVisible()
    }

    @Test
    fun managesActivationLinkFromEditUserPage(page: Page) {
        saveUser("admin", "password", UserType.ADMIN)
        val alice = saveUser("alice", "password", UserType.USER, active = false)
        saveActivationToken(alice, "activation-token")
        setStoredToken(page, testAuthTokens.issueToken("admin", UserType.ADMIN))
        page.context().grantPermissions(listOf("clipboard-write"))

        page.navigate(server.url.toString() + "/user-management/${alice.id}")

        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Activation required")
        assertThat(page.getByLabel("Activation link")).hasValue(
            Pattern.compile("http://localhost:8080/activate-account\\?token=activation-token"),
        )

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Copy link")).click()
        assertThat(page.getByText("Activation link copied.")).isVisible()

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Regenerate activation link")).click()
        assertThat(page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Regenerate activation link?"))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Regenerate link")).click()

        page.shouldEventually {
            val nextToken = userActivationTokenRepository.findByUserId(alice.id!!)?.token
            (nextToken != null && nextToken != "activation-token").shouldBe(true)
        }
        assertThat(page.getByText("Activation link regenerated.")).isVisible()
        assertThat(page.getByLabel("Activation link")).not().hasValue(
            Pattern.compile(".*token=activation-token$"),
        )
    }

    @Test
    fun showsActivationErrorWhenInactiveUserHasNoValidToken(page: Page) {
        saveUser("admin", "password", UserType.ADMIN)
        val alice = saveUser("alice", "password", UserType.USER, active = false)
        setStoredToken(page, testAuthTokens.issueToken("admin", UserType.ADMIN))

        page.navigate(server.url.toString() + "/user-management/${alice.id}")

        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Activation unavailable")
    }

    private fun saveUser(username: String, password: String, type: UserType, active: Boolean = true): User {
        return userRepository.save(User(username = username, passwordHash = passwordHasher.hash(password), type = type, active = active))
    }

    private fun saveActivationToken(user: User, token: String) {
        userActivationTokenRepository.save(
            UserActivationToken(
                userId = user.id!!,
                token = token,
                expiresAt = Instant.parse("2099-06-15T08:00:00Z"),
            ),
        )
    }
}
