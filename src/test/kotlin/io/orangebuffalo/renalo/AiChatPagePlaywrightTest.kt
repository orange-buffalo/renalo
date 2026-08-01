package io.orangebuffalo.renalo

import com.microsoft.playwright.Page
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.collections.shouldContainExactly
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
@Property(name = "renalo.ai-chat.enabled", value = "true")
class AiChatPagePlaywrightTest : IntegrationTestSupport() {
    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var passwordHasher: PasswordHasher

    @Inject
    lateinit var testAuthTokens: TestAuthTokens

    @Test
    fun persistsRenamesAndDeletesConversations(page: Page) {
        saveUser("alice", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/chat")

        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Chat"))).isVisible()
        mainNavigationLabels(page).shouldContainExactly("Dashboard", "Expenses", "Incomes", "Transfers", "Chat")
        assertAccountMenuItems(page, listOf("Settings", "My Profile"))
        assertThat(page.getByLabel("Send message")).isDisabled()

        val messageInput = page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        )
        messageInput.fill("Discard this draft")
        openChatActions(page)
        page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Delete chat")).click()
        assertThat(page.getByRole(AriaRole.DIALOG)).not().isVisible()
        messageInput.inputValue().shouldBe("")

        messageInput.fill("How was this month?")
        page.getByLabel("Send message").click()

        page.shouldEventually {
            extractMessages().shouldContainExactly(
                ChatMessage("You", "How was this month?", emptyList()),
                ChatMessage(
                    "Renalo",
                    "Spending snapshot\n\nYou asked: How was this month?\n\nHere is an example of how an AI-generated answer could present your results:\n\nCategory\tAmount\tShare\nGroceries\t${'$'}428.30\t42%\nTransport\t${'$'}186.75\t18%\nDining out\t${'$'}142.10\t14%\nGroceries were the largest expense category.\nDining out was lower than groceries by ${'$'}286.20.\nThe remaining categories accounted for 26% of the sample total.\n\nThis is placeholder data from the Chat preview. It is not calculated from your Renalo records yet.",
                    listOf(ToolActivity("Reviewed expense totals", "COMPLETED")),
                ),
            )
        }
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Spending snapshot"))).isVisible()
        assertThat(page.getByRole(AriaRole.TABLE)).isVisible()
        assertThat(page.locator("[data-chat-author='Renalo'] [data-streamdown='strong']").first())
            .containsText("How was this month?")
        page.getByTitle("Copy table").click()
        assertThat(page.getByTitle("Copy table as Markdown")).isVisible()
        page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Spending snapshot")).click()

        openChatActions(page)
        page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Rename chat")).click()
        val renameDialog = page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Rename chat"))
        val chatName = renameDialog.getByLabel("Chat name")
        chatName.inputValue().shouldBe("New chat")
        chatName.fill("Monthly review")
        renameDialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Save name")).click()
        assertThat(renameDialog).not().isVisible()
        assertThat(conversationSelector(page)).containsText("Monthly review")

        page.reload()
        assertConversationOptions(page, listOf("Monthly review", "New chat"))
        conversationSelector(page).click()
        page.getByRole(AriaRole.MENUITEMRADIO, Page.GetByRoleOptions().setName("Monthly review")).click()
        assertThat(
            page.getByText(
                "This chat is saved, but previous preview messages are not stored and cannot be displayed yet.",
            ),
        ).isVisible()

        openChatActions(page)
        page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Delete chat")).click()
        val deleteDialog = page.getByRole(
            AriaRole.DIALOG,
            Page.GetByRoleOptions().setName("Delete “Monthly review”?"),
        )
        assertThat(deleteDialog).isVisible()
        deleteDialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Cancel")).click()
        assertThat(deleteDialog).not().isVisible()

        openChatActions(page)
        page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Delete chat")).click()
        page.getByRole(
            AriaRole.DIALOG,
            Page.GetByRoleOptions().setName("Delete “Monthly review”?"),
        ).getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Delete chat")).click()
        assertThat(conversationSelector(page)).containsText("New chat")
        assertConversationOptions(page, listOf("New chat"))
    }

    @Test
    fun keepsComposerVisibleWhileTheMessageFeedScrollsOnMobile(page: Page) {
        saveUser("alice", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.setViewportSize(390, 844)
        page.navigate(server.url.toString() + "/chat")

        repeat(3) { index ->
            page.getByRole(
                AriaRole.TEXTBOX,
                Page.GetByRoleOptions().setName("Message").setExact(true),
            ).fill("Question ${index + 1}")
            page.getByLabel("Send message").click()
            page.shouldEventually {
                page.locator("[data-chat-author='Renalo']").count().shouldBe(index + 1)
            }
        }

        val messageInput = page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        )
        assertThat(messageInput).isVisible()
        assertThat(page.getByLabel("Send message")).isVisible()
        page.locator(".ai-chat-feed").evaluate(
            "feed => feed.scrollHeight > feed.clientHeight && feed.scrollTop > 0",
        ).shouldBe(true)

        messageInput.fill((1..8).joinToString("\n") { "Draft line $it" })
        messageInput.evaluate("input => input.scrollHeight > input.clientHeight").shouldBe(true)
        assertThat(page.getByLabel("Send message")).isVisible()
    }

    @Test
    fun cancelsAStreamingResponse(page: Page) {
        saveUser("alice", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.navigate(server.url.toString() + "/chat")

        page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        ).fill("Stop this response")
        page.getByLabel("Send message").click()

        assertThat(page.getByLabel("Stop response")).isVisible()
        assertThat(page.getByText("Reviewing expense totals")).isVisible()
        page.getByLabel("Stop response").click()

        page.shouldEventually {
            extractMessages().shouldContainExactly(
                ChatMessage("You", "Stop this response", emptyList()),
                ChatMessage(
                    "Renalo",
                    "",
                    listOf(ToolActivity("Reviewing expense totals · Stopped", "CANCELLED")),
                ),
            )
        }
        assertThat(page.getByLabel("Stop response")).not().isVisible()
        assertThat(page.getByLabel("Send message")).isDisabled()
    }

    @Test
    fun preservesPartialContentWhenTheStreamIsInterrupted(page: Page) {
        saveUser("alice", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.route("**/api/ai-chat/messages") { route ->
            route.fulfill(
                Route.FulfillOptions()
                    .setContentType("application/x-ndjson")
                    .setBody(
                        """
                            {"v":1,"seq":1,"type":"turn.started"}
                            {"v":1,"seq":2,"type":"assistant.delta","text":"## Partial response"}
                        """.trimIndent() + "\n",
                    ),
            )
        }
        page.navigate(server.url.toString() + "/chat")

        page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        ).fill("Interrupt this response")
        page.getByLabel("Send message").click()

        assertThat(page.getByText("The response was interrupted. Partial content remains in this conversation."))
            .isVisible()
        page.shouldEventually {
            extractMessages().shouldContainExactly(
                ChatMessage("You", "Interrupt this response", emptyList()),
                ChatMessage("Renalo", "Partial response", emptyList()),
            )
        }
        assertThat(page.getByLabel("Stop response")).not().isVisible()
    }

    @Test
    fun safelyRendersAssistantMarkdown(page: Page) {
        saveUser("alice", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.route("**/api/ai-chat/messages") { route ->
            route.fulfill(
                Route.FulfillOptions()
                    .setContentType("application/x-ndjson")
                    .setBody(
                        """
                            {"v":1,"seq":1,"type":"turn.started"}
                            {"v":1,"seq":2,"type":"assistant.delta","text":"## Safe response\n<script>window.__unsafeMarkdownExecuted = true</script>\n![tracker](https://example.com/tracker.png)\n[Unsafe link](javascript:alert('unsafe'))\n[Allowed link](https://example.com/details)"}
                            {"v":1,"seq":3,"type":"turn.completed"}
                        """.trimIndent() + "\n",
                    ),
            )
        }

        page.navigate(server.url.toString() + "/chat")
        page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        ).fill("Show a safe response")
        page.getByLabel("Send message").click()

        val assistantMessage = page.locator("[data-chat-author='Renalo']")
        assertThat(assistantMessage.getByRole(AriaRole.HEADING)).containsText("Safe response")
        assistantMessage.locator("script").count().shouldBe(0)
        assistantMessage.locator("img").count().shouldBe(0)
        assistantMessage.getByRole(AriaRole.LINK).count().shouldBe(0)
        assertThat(assistantMessage.locator("[data-streamdown='link']")).containsText("Allowed link")
        page.evaluate("window.__unsafeMarkdownExecuted === true").shouldBe(false)
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
        page.shouldEventually {
            conversationSelector(page).click()
            page.getByRole(AriaRole.MENUITEMRADIO)
                .allTextContents()
                .map(String::trim)
                .shouldContainExactly(expected)
        }
        page.keyboard().press("Escape")
    }

    private fun openChatActions(page: Page) {
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Chat actions")).click()
    }

    private fun conversationSelector(page: Page) = page.getByRole(
        AriaRole.BUTTON,
        Page.GetByRoleOptions().setName("Select conversation"),
    )

    private fun Page.extractMessages(): List<ChatMessage> = locator("[data-chat-author]").all().map { message ->
        ChatMessage(
            author = message.getAttribute("data-chat-author"),
            content = message.locator(".ai-chat-message-content").innerText(),
            toolActivities = message.locator(".ai-chat-tool-activity").all().map { activity ->
                ToolActivity(
                    label = activity.innerText().replace(Regex("\\s+"), " ").trim(),
                    status = activity.getAttribute("data-tool-status"),
                )
            },
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
        val toolActivities: List<ToolActivity>,
    )

    private data class ToolActivity(
        val label: String,
        val status: String,
    )
}
