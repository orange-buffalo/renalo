package io.orangebuffalo.renalo

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
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
        page.route("**/api/refresh-access-token") { route ->
            route.fulfill(
                Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("""{"token":"$refreshedToken"}"""),
            )
        }
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/tracking")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
        page.waitForFunction("token => window.localStorage.getItem('renalo.authToken') === token", refreshedToken)
    }

    private fun saveUser(username: String, password: String, type: UserType): User {
        return userRepository.save(User(username = username, passwordHash = passwordHasher.hash(password), type = type))
    }

    private fun tokenPayloadWithExpiration(secondsFromNow: Long): String {
        val payload = """{"exp":${testTimeProvider.now().plusSeconds(secondsFromNow).epochSecond}}"""
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
    }
}
