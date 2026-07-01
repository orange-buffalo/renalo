package io.orangebuffalo.renalo

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.shouldBe
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
@Property(name = "renalo.auth.access-token-expiration-seconds", value = "35")
class RememberMeRefreshPlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun refreshesAccessTokenBeforeItExpires(page: Page) {
        saveUser("alice", "password", UserType.USER)
        val refreshedToken = "refresh-timer-token.${tokenPayloadWithExpiration(1800)}.signature"
        val storedToken = testAuthTokens.issueToken("alice", UserType.USER)
        page.route("**/api/refresh-access-token") { route ->
            route.request().headers()["authorization"].shouldBe("Bearer $storedToken")
            route.fulfill(
                Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("""{"token":"$refreshedToken"}"""),
            )
        }
        setStoredToken(page, storedToken)

        page.navigate(server.url.toString() + "/tracking")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
        page.waitForFunction("token => window.localStorage.getItem('renalo.authToken') === token", refreshedToken)
    }

    @Test
    fun redirectsToExpiredSessionWhenTokenExpiresAfterRefreshReturnsNull(page: Page) {
        saveUser("alice", "password", UserType.USER)
        val storedToken = testAuthTokens.issueToken("alice", UserType.USER)
        page.route("**/api/refresh-access-token") { route ->
            route.request().headers()["authorization"].shouldBe("Bearer $storedToken")
            route.fulfill(
                Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("""{"token":null}"""),
            )
        }
        page.navigate(server.url.toString() + "/")
        page.evaluate("window.localStorage.setItem('renalo.authToken', '$storedToken')")

        page.navigate(server.url.toString() + "/tracking")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
        page.waitForURL("**/?sessionExpired=true", Page.WaitForURLOptions().setTimeout(40_000.0))
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Sign in to Renalo"))).isVisible()
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("Session expired")
    }

    private fun saveUser(username: String, password: String, type: UserType): User {
        return userRepository.save(User(username = username, passwordHash = passwordHasher.hash(password), type = type))
    }

    private fun tokenPayloadWithExpiration(secondsFromNow: Long): String {
        val payload = """{"exp":${testTimeProvider.now().plusSeconds(secondsFromNow).epochSecond}}"""
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
    }
}
