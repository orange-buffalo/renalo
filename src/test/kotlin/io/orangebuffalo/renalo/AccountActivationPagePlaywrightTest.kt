package io.orangebuffalo.renalo

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserActivationToken
import io.orangebuffalo.renalo.user.UserActivationTokenRepository
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import java.time.Instant
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class AccountActivationPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var userActivationTokenRepository: UserActivationTokenRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Test
    fun activatesAccountFromActivationPage(page: Page) {
        val alice = saveUser("alice", "old-password", active = false)
        saveActivationToken(alice, "valid-token")

        page.navigate(server.url.toString() + "/activate-account?token=valid-token")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Set your password"))).isVisible()
        assertThat(page.getByLabel("Username")).hasValue("alice")
        assertThat(page.getByLabel("Username")).isDisabled()

        page.locator("input[name='password']").fill("new-password")
        page.locator("input[name='passwordConfirmation']").fill("new-password")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Activate account")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Sign in to Renalo"))).isVisible()
        assertThat(page.getByText("Login now with your credentials.")).isVisible()
        val activatedUser = userRepository.findByUsername("alice")!!
        activatedUser.active.shouldBe(true)
        passwordHasher.verify("new-password", activatedUser.passwordHash).shouldBe(true)
        userActivationTokenRepository.findByToken("valid-token").shouldBeNull()

        page.getByLabel("Username").fill("alice")
        page.locator("input[name='password']").fill("new-password")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Expense tracking"))).isVisible()
    }

    @Test
    fun showsExpiredLinkMessageForUnknownActivationToken(page: Page) {
        page.navigate(server.url.toString() + "/activate-account?token=unknown-token")

        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Activation link expired")
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Contact your server administrator")
        assertThat(page.getByRole(AriaRole.TEXTBOX)).not().isVisible()
        assertThat(page.getByRole(AriaRole.BUTTON)).not().isVisible()
    }

    private fun saveUser(username: String, password: String, active: Boolean): User {
        return userRepository.save(
            User(
                username = username,
                passwordHash = passwordHasher.hash(password),
                type = UserType.USER,
                active = active,
            ),
        )
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
