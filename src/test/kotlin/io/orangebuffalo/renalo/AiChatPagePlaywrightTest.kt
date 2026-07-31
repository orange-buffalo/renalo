package io.orangebuffalo.renalo

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.collections.shouldContainExactly
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
@Property(name = "renalo.ai-chat.enabled", value = "true")
class AiChatPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun managesInMemoryConversations(page: Page) {
        saveUser("alice", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/chat")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Chat"))).isVisible()
        mainNavigationLabels(page).shouldContainExactly("Dashboard", "Expenses", "Incomes", "Transfers", "Chat")
        assertAccountMenuItems(page, listOf("Settings", "My Profile"))
        assertThat(page.getByLabel("Send message")).isDisabled()

        page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        ).fill("How was this month?")
        page.getByLabel("Send message").click()

        page.shouldEventually {
            extractMessages().shouldContainExactly(
                ChatMessage("You", "How was this month?"),
                ChatMessage(
                    "Renalo",
                    "You asked: How was this month?\n\nLorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.\n\nDuis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.",
                ),
            )
        }

        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("New conversation")).click()
        assertThat(page.getByText("What would you like to explore?")).isVisible()
        assertConversationOptions(page, listOf("Conversation 1", "Conversation 2"))

        conversationSelect(page).click()
        page.getByRole(AriaRole.OPTION, Page.GetByRoleOptions().setName("Conversation 1")).click()
        page.shouldEventually {
            extractMessages().shouldContainExactly(
                ChatMessage("You", "How was this month?"),
                ChatMessage(
                    "Renalo",
                    "You asked: How was this month?\n\nLorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.\n\nDuis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.",
                ),
            )
        }
    }

    @Test
    fun hidesChatWhenDisabled(page: Page) {
        saveUser("alice", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.route("**/api/system-settings") { route ->
            route.fulfill(
                Route.FulfillOptions()
                    .setContentType("application/json")
                    .setBody("""{ "publicUrl": "http://localhost:8080", "aiChatEnabled": false }"""),
            )
        }

        page.navigate(server.url.toString() + "/chat")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Dashboard"))).isVisible()
        mainNavigationLabels(page).shouldContainExactly("Dashboard", "Expenses", "Incomes", "Transfers")
        assertAccountMenuItems(page, listOf("Settings", "My Profile"))
    }

    @Test
    fun doesNotExposeChatOrBudgetSettingsToAdmins(page: Page) {
        saveUser("admin", UserType.ADMIN)
        setStoredToken(page, testAuthTokens.issueToken("admin", UserType.ADMIN))

        page.navigate(server.url.toString() + "/chat")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("User management"))).isVisible()
        mainNavigationLabels(page).shouldContainExactly("User management")
        assertAccountMenuItems(page, listOf("My Profile"))
    }

    private fun mainNavigationLabels(page: Page): List<String> = page
        .getByRole(AriaRole.NAVIGATION, Page.GetByRoleOptions().setName("Main navigation"))
        .getByRole(AriaRole.LINK)
        .all()
        .map { it.getAttribute("aria-label") }

    private fun assertAccountMenuItems(page: Page, expected: List<String>) {
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open account menu")).click()
        page.getByRole(AriaRole.MENU, Page.GetByRoleOptions().setName("Account menu"))
            .getByRole(AriaRole.MENUITEM)
            .allTextContents()
            .map(String::trim)
            .shouldContainExactly(expected)
        page.keyboard().press("Escape")
    }

    private fun assertConversationOptions(page: Page, expected: List<String>) {
        conversationSelect(page).click()
        page.getByRole(AriaRole.OPTION).allTextContents().map(String::trim).shouldContainExactly(expected)
        page.keyboard().press("Escape")
    }

    private fun conversationSelect(page: Page) = page.locator(".ai-chat-conversation-select button")

    private fun Page.extractMessages(): List<ChatMessage> = locator("[data-chat-author]").all().map { message ->
        ChatMessage(
            author = message.getAttribute("data-chat-author"),
            content = message.locator(".ai-chat-message-content").innerText(),
        )
    }

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = type,
        ),
    )

    private data class ChatMessage(
        val author: String,
        val content: String,
    )
}
