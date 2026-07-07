package io.orangebuffalo.renalo

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class LoginPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun logsUserIntoTrackingPage(page: Page) {
        saveUser("alice", "password", UserType.USER)

        page.navigate(server.url.toString() + "/")
        page.getByLabel("Username").fill("alice")
        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Password")).fill("password")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in").setExact(true)).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
        assertAccountMenuTrigger(page, "alice", "USER")
        assertThat(page.getByRole(AriaRole.NAVIGATION, Page.GetByRoleOptions().setName("Main navigation"))).isVisible()
        assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
        assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Expenses"))).isVisible()
    }

    @Test
    fun remembersUserWhenRememberMeIsSelected(page: Page) {
        saveUser("alice", "password", UserType.USER)

        page.navigate(server.url.toString() + "/")
        page.getByLabel("Username").fill("alice")
        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Password")).fill("password")
        page.locator(".login-remember-checkbox").click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in").setExact(true)).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()

        val refreshedToken = page.evaluate(
            """
                async () => {
                  const response = await fetch('/api/refresh-access-token', { method: 'POST' });
                  const body = await response.json();
                  return body.token;
                }
            """.trimIndent(),
        ) as String?

        refreshedToken.shouldNotBeBlank()
    }

    @Test
    fun togglesRememberMeOnMobile(page: Page) {
        page.setViewportSize(390, 844)
        page.navigate(server.url.toString() + "/")

        page.locator(".login-remember-checkbox").click()

        val rememberMePreference = page.evaluate("localStorage.getItem('renalo.rememberMe')")
        rememberMePreference.shouldBe("true")
    }

    @Test
    fun keepsUserOnTrackingPageAfterRefresh(page: Page) {
        saveUser("alice", "password", UserType.USER)

        page.navigate(server.url.toString() + "/")
        page.getByLabel("Username").fill("alice")
        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Password")).fill("password")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in").setExact(true)).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()

        page.reload()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
        assertAccountMenuTrigger(page, "alice", "USER")
    }

    @Test
    fun initializesProfileForDirectRequestedRouteWithStoredToken(page: Page) {
        saveUser("alice", "password", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/tracking")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
        assertAccountMenuTrigger(page, "alice", "USER")
    }

    @Test
    fun showsLoadingContentDuringProfileBootstrap(page: Page) {
        saveUser("alice", "password", UserType.USER)
        val releaseProfileRequest = CountDownLatch(1)
        val routeFailure = AtomicReference<Throwable>()
        val profileRequestGate = Executors.newSingleThreadExecutor()
        try {
            page.route("**/api/profile") { route ->
                profileRequestGate.submit {
                    try {
                        if (!releaseProfileRequest.await(10, TimeUnit.SECONDS)) {
                            routeFailure.set(AssertionError("Timed out waiting to release /api/profile request"))
                        }
                    } catch (interruptedException: InterruptedException) {
                        Thread.currentThread().interrupt()
                        routeFailure.set(interruptedException)
                    } finally {
                        route.resume()
                    }
                }
            }

            setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
            page.navigate(server.url.toString() + "/")
            page.evaluate("setTimeout(() => { window.location.href = '/tracking'; }, 0)")

            try {
                assertThat(page.locator("#app-loader")).isVisible()
            } finally {
                releaseProfileRequest.countDown()
            }
            assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
            routeFailure.get().shouldBeNull()
        } finally {
            releaseProfileRequest.countDown()
            profileRequestGate.shutdown()
            profileRequestGate.awaitTermination(10, TimeUnit.SECONDS).shouldBe(true)
        }
    }

    @Test
    fun redirectsAdminAwayFromTrackingPage(page: Page) {
        saveUser("admin", "password", UserType.ADMIN)
        setStoredToken(page, testAuthTokens.issueToken("admin", UserType.ADMIN))

        page.navigate(server.url.toString() + "/tracking")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("User management"))).isVisible()
        assertAccountMenuTrigger(page, "admin", "ADMIN")
    }

    @Test
    fun opensProfileAndSignsOutFromAccountMenu(page: Page) {
        saveUser("alice", "password", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.navigate(server.url.toString() + "/tracking")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open account menu")).click()
        assertThat(page.getByRole(AriaRole.MENU, Page.GetByRoleOptions().setName("Account menu"))).isVisible()
        page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("My Profile")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("My profile"))).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open account menu")).click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign out")).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Sign in to Renalo"))).isVisible()
        page.evaluate("window.localStorage.getItem('renalo.authToken')").shouldBeNull()
    }

    @Test
    fun clearsExpiredStoredTokenAndShowsLoginPage(page: Page) {
        saveUser("alice", "password", UserType.USER)
        setStoredToken(page, testAuthTokens.issueExpiredToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/tracking")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Sign in to Renalo"))).isVisible()
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Session expired")
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Please sign in again to continue.")
        page.evaluate("window.localStorage.getItem('renalo.authToken')").shouldBeNull()
    }

    @Test
    fun redirectsToLoginWhenRuntimeApiRequestReturnsUnauthorized(page: Page) {
        saveUser("alice", "password", UserType.USER)
        page.navigate(server.url.toString() + "/")
        val token = testAuthTokens.issueToken("alice", UserType.USER)
        page.evaluate("window.localStorage.setItem('renalo.authToken', '$token')")
        page.navigate(server.url.toString() + "/tracking")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
        page.route("**/api/tracking/transactions/EXPENSE**") { route ->
            route.fulfill(Route.FulfillOptions().setStatus(401))
        }

        page.navigate(server.url.toString() + "/expenses")
        page.waitForURL("**/?sessionExpired=true")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Sign in to Renalo"))).isVisible()
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Session expired")
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Please sign in again to continue.")
        page.evaluate("window.localStorage.getItem('renalo.authToken')").shouldBeNull()
    }

    @Test
    fun usesClientSideNavigationForMainNavigationLinks(page: Page) {
        saveUser("alice", "password", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.navigate(server.url.toString() + "/tracking")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()

        page.evaluate("window.__renaloNavProbe = 'kept' ")
        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("Dashboard")).click()

        page.evaluate("window.__renaloNavProbe").shouldBe("kept")
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
    }

    @Test
    fun logsAdminIntoUserManagementPage(page: Page) {
        saveUser("admin", "password", UserType.ADMIN)

        page.navigate(server.url.toString() + "/")
        page.getByLabel("Username").fill("admin")
        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Password")).fill("password")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in").setExact(true)).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("User management"))).isVisible()
        assertAccountMenuTrigger(page, "admin", "ADMIN")
        assertThat(page.getByRole(AriaRole.NAVIGATION, Page.GetByRoleOptions().setName("Main navigation"))).isVisible()
        assertThat(page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("User management"))).isVisible()
    }

    @Test
    fun showsErrorForWrongPassword(page: Page) {
        saveUser("alice", "password", UserType.USER)

        page.navigate(server.url.toString() + "/")
        page.getByLabel("Username").fill("alice")
        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Password")).fill("wrong-password")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in").setExact(true)).click()

        assertThat(page.getByText("Invalid username or password.")).isVisible()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Sign in to Renalo"))).isVisible()
    }

    @Test
    fun allowsPasswordSignInAfterPasskeySignInFails(page: Page) {
        saveUser("alice", "password", UserType.USER)
        page.route("**/api/passkeys/authentication-options") { route ->
            route.fulfill(Route.FulfillOptions().setStatus(400))
        }

        page.navigate(server.url.toString() + "/")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in with passkey")).click()

        assertThat(page.getByText("Passkey sign in failed.")).isVisible()

        page.getByLabel("Username").fill("alice")
        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Password")).fill("password")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Sign in").setExact(true)).click()

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
    }

    private fun saveUser(username: String, password: String, type: UserType): User {
        return userRepository.save(User(username = username, passwordHash = passwordHasher.hash(password), type = type))
    }

    private fun assertAccountMenuTrigger(page: Page, username: String, type: String) {
        val trigger = page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open account menu"))
        assertThat(trigger).isVisible()
        assertThat(trigger.getByText(username, Locator.GetByTextOptions().setExact(true))).isVisible()
        assertThat(trigger.getByText(type, Locator.GetByTextOptions().setExact(true))).isVisible()
    }
}
