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
import io.orangebuffalo.renalo.ai.AiChatConversation
import io.orangebuffalo.renalo.ai.AiChatConversationEventService
import io.orangebuffalo.renalo.ai.AiChatConversationRepository
import io.orangebuffalo.renalo.ai.AiChatChartKind
import io.orangebuffalo.renalo.ai.AiChatChartSource
import io.orangebuffalo.renalo.ai.AiChatChartSourcePoint
import io.orangebuffalo.renalo.ai.AiChatChartSourceSegment
import io.orangebuffalo.renalo.ai.AiChatCharts
import io.orangebuffalo.renalo.ai.AiChatModelToolCall
import io.orangebuffalo.renalo.tracking.ExpenseCategory
import io.orangebuffalo.renalo.tracking.ExpenseCategoryRepository
import io.orangebuffalo.renalo.tracking.TrackingAccount
import io.orangebuffalo.renalo.tracking.TrackingAccountRepository
import io.orangebuffalo.renalo.tracking.Transaction
import io.orangebuffalo.renalo.tracking.TransactionRepository
import io.orangebuffalo.renalo.tracking.TransactionType
import io.orangebuffalo.renalo.user.PasswordHasher
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserRepository
import io.orangebuffalo.renalo.user.UserType
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.time.LocalDate

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

    @Inject
    lateinit var conversationRepository: AiChatConversationRepository

    @Inject
    lateinit var conversationEventService: AiChatConversationEventService

    @Inject lateinit var charts: AiChatCharts
    @Inject lateinit var trackingAccountRepository: TrackingAccountRepository
    @Inject lateinit var expenseCategoryRepository: ExpenseCategoryRepository
    @Inject lateinit var transactionRepository: TransactionRepository

    @Test
    fun streamsAndReloadsAssistantCharts(page: Page) {
        val user = saveUser("alice", UserType.USER)
        val account = trackingAccountRepository.save(
            TrackingAccount(userId = user.id!!, name = "Main", currency = "AUD", initialBalanceMinor = 0, isDefault = true),
        )
        val category = expenseCategoryRepository.save(ExpenseCategory(userId = user.id!!, name = "Groceries"))
        transactionRepository.save(
            Transaction(
                userId = user.id!!,
                type = TransactionType.EXPENSE,
                trackingAccountId = account.id!!,
                categoryId = category.id!!,
                date = LocalDate.parse("2026-08-01"),
                amountMinor = 2_345,
                defaultCurrencyAmountMinor = 2_345,
                defaultCurrency = "AUD",
            ),
        )
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.navigate(server.url.toString() + "/chat")

        page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Message").setExact(true))
            .fill("Show a chart of spending")
        page.getByLabel("Send message").click()

        page.shouldEventually {
            extractChartData().shouldContainExactly(
                ChartData(
                    title = "Expenses by category",
                    kind = "DONUT",
                    rows = listOf(ChartRow("Donut segment", "Groceries", "A${'$'}23.45")),
                ),
            )
        }
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Spending snapshot"))).isVisible()

        page.reload()
        page.shouldEventually {
            extractChartData().shouldContainExactly(
                ChartData(
                    title = "Expenses by category",
                    kind = "DONUT",
                    rows = listOf(ChartRow("Donut segment", "Groceries", "A${'$'}23.45")),
                ),
            )
        }
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Spending snapshot"))).isVisible()
    }

    @Test
    fun rendersLinePieAndDonutChartsFromSavedHistory(page: Page) {
        val user = saveUser("alice", UserType.USER)
        val conversation = conversationRepository.save(AiChatConversation(userId = user.id!!, title = "Chart gallery"))
        val chartValues = listOf(
            charts.create(
                AiChatChartKind.LINE,
                "Balance trend",
                AiChatChartSource.Line(
                    "AUD",
                    "Balance",
                    listOf(
                        AiChatChartSourcePoint(LocalDate.parse("2026-01-01"), 1_000),
                        AiChatChartSourcePoint(LocalDate.parse("2026-02-01"), 2_500),
                    ),
                ),
                "00000000-0000-0000-0000-000000000101",
            ),
            charts.create(
                AiChatChartKind.PIE,
                "Expense share",
                AiChatChartSource.Slices(
                    "AUD",
                    listOf(AiChatChartSourceSegment("Food", 1_200), AiChatChartSourceSegment("Rent", 8_800)),
                ),
                "00000000-0000-0000-0000-000000000102",
            ),
            charts.create(
                AiChatChartKind.DONUT,
                "Income share",
                AiChatChartSource.Slices(
                    "AUD",
                    listOf(AiChatChartSourceSegment("Salary", 9_000), AiChatChartSourceSegment("Interest", 1_000)),
                ),
                "00000000-0000-0000-0000-000000000103",
            ),
        )
        val items = mutableListOf(conversationEventService.userMessage("Show all chart styles"))
        chartValues.forEachIndexed { index, chart ->
            val call = AiChatModelToolCall("chart-$index", "present_chart", "{}")
            items += """{"type":"function_call","call_id":"${call.id}","name":"present_chart","arguments":"{}"}"""
            items += conversationEventService.toolResult(call, charts.encodeArtifact(chart))
        }
        items += """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Here are the requested charts."}]}"""
        conversationEventService.appendItems(user.id!!, conversation.id!!, items)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/chat")

        page.shouldEventually {
            extractChartData().shouldContainExactly(
                ChartData(
                    "Balance trend",
                    "LINE",
                    listOf(
                        ChartRow("Balance", "2026-01-01", "A${'$'}10.00"),
                        ChartRow("Balance", "2026-02-01", "A${'$'}25.00"),
                    ),
                ),
                ChartData(
                    "Expense share",
                    "PIE",
                    listOf(
                        ChartRow("Pie segment", "Food", "A${'$'}12.00"),
                        ChartRow("Pie segment", "Rent", "A${'$'}88.00"),
                    ),
                ),
                ChartData(
                    "Income share",
                    "DONUT",
                    listOf(
                        ChartRow("Donut segment", "Salary", "A${'$'}90.00"),
                        ChartRow("Donut segment", "Interest", "A${'$'}10.00"),
                    ),
                ),
            )
        }
    }

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
        assertThat(page.getByLabel("Chat actions")).isDisabled()
        assertThat(page.getByLabel("New conversation")).isDisabled()
        messageInput.fill("")

        messageInput.fill("How was this month?")
        page.getByLabel("Send message").click()

        page.shouldEventually {
            extractMessages().shouldContainExactly(
                ChatMessage("You", "How was this month?", emptyList()),
                ChatMessage(
                    "Renalo",
                    "Spending snapshot\n\nYou asked: How was this month?\n\nHere is an example of how an AI-generated answer could present your results:\n\nCategory\tAmount\tShare\nGroceries\t${'$'}428.30\t42%\nTransport\t${'$'}186.75\t18%\nDining out\t${'$'}142.10\t14%\nGroceries were the largest expense category.\nDining out was lower than groceries by ${'$'}286.20.\nThe remaining categories accounted for 26% of the sample total.\n\nThis response was generated from Renalo's read-only financial tools.",
                    listOf(ToolActivity("Calculated category totals", "COMPLETED")),
                ),
            )
        }
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Spending snapshot"))).isVisible()
        assertThat(page.getByRole(AriaRole.TABLE)).isVisible()
        assertThat(page.locator("[data-chat-author='Renalo'] [data-streamdown='strong']").first())
            .containsText("How was this month?")
        assertThat(conversationSelector(page)).containsText("Monthly spending review")
        assertThat(page.getByLabel("Chat actions")).isEnabled()
        assertThat(page.getByLabel("New conversation")).isEnabled()
        page.getByTitle("Copy table").click()
        assertThat(page.getByTitle("Copy table as Markdown")).isVisible()
        page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Spending snapshot")).click()

        openChatActions(page)
        page.getByRole(AriaRole.MENUITEM, Page.GetByRoleOptions().setName("Rename chat")).click()
        val renameDialog = page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Rename chat"))
        val chatName = renameDialog.getByLabel("Chat name")
        chatName.inputValue().shouldBe("Monthly spending review")
        chatName.fill("Monthly review")
        renameDialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Save name")).click()
        assertThat(renameDialog).not().isVisible()
        assertThat(conversationSelector(page)).containsText("Monthly review")
        page.getByLabel("New conversation").click()
        assertThat(page.getByLabel("Chat actions")).isDisabled()
        assertThat(page.getByLabel("New conversation")).isDisabled()
        messageInput.fill("Show my spending")
        page.getByLabel("Send message").click()
        page.shouldEventually {
            conversationSelector(page).textContent().shouldBe("Spending review")
        }

        page.reload()
        assertThat(conversationSelector(page)).containsText("Spending review")
        assertConversationOptions(page, listOf("Spending review", "Monthly review", "New chat"))
        conversationSelector(page).click()
        page.getByRole(AriaRole.MENUITEMRADIO, Page.GetByRoleOptions().setName("Monthly review")).click()
        page.shouldEventually {
            extractMessages().shouldContainExactly(
                ChatMessage("You", "How was this month?", emptyList()),
                ChatMessage(
                    "Renalo",
                    "Spending snapshot\n\nYou asked: How was this month?\n\nHere is an example of how an AI-generated answer could present your results:\n\nCategory\tAmount\tShare\nGroceries\t${'$'}428.30\t42%\nTransport\t${'$'}186.75\t18%\nDining out\t${'$'}142.10\t14%\nGroceries were the largest expense category.\nDining out was lower than groceries by ${'$'}286.20.\nThe remaining categories accounted for 26% of the sample total.\n\nThis response was generated from Renalo's read-only financial tools.",
                    emptyList(),
                ),
            )
        }

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
        assertThat(conversationSelector(page)).containsText("Spending review")
        assertConversationOptions(page, listOf("Spending review", "New chat"))
    }

    @Test
    fun loadsSavedHistoryFromThePersistedEventLog(page: Page) {
        val user = saveUser("alice", UserType.USER)
        val available = conversationRepository.save(
            AiChatConversation(
                userId = user.id!!,
                title = "Available chat",
            ),
        )
        conversationRepository.save(
            AiChatConversation(
                userId = user.id!!,
                title = "Empty chat",
            ),
        )
        conversationEventService.appendItems(
            user.id!!,
            available.id!!,
            listOf(
                conversationEventService.userMessage("What did we discuss in this chat?"),
                """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"## Saved conversation\n\nThis history was loaded from Renalo's event log."}]}""",
            ),
        )
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))

        page.navigate(server.url.toString() + "/chat")

        val messageInput = page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        )
        assertThat(messageInput).isEnabled()

        conversationSelector(page).click()
        page.getByRole(AriaRole.MENUITEMRADIO, Page.GetByRoleOptions().setName("Available chat")).click()
        page.shouldEventually {
            extractMessages().shouldContainExactly(
                ChatMessage("You", "What did we discuss in this chat?", emptyList()),
                ChatMessage(
                    "Renalo",
                    "Saved conversation\n\nThis history was loaded from Renalo's event log.",
                    emptyList(),
                ),
            )
        }
        assertThat(messageInput).isEnabled()
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
        assertThat(page.getByText("Calculating category totals")).isVisible()
        page.getByLabel("Stop response").click()

        page.shouldEventually {
            extractMessages().shouldContainExactly(
                ChatMessage("You", "Stop this response", emptyList()),
                ChatMessage(
                    "Renalo",
                    "",
                    listOf(ToolActivity("Calculating category totals · Stopped", "CANCELLED")),
                ),
            )
        }
        assertThat(page.getByLabel("Stop response")).not().isVisible()
        assertThat(page.getByLabel("Send message")).isDisabled()
    }

    @Test
    fun showsThinkingBeforeTheFirstResponseActivity(page: Page) {
        saveUser("alice", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.navigate(server.url.toString() + "/chat")

        page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        ).fill("Slow title request")
        page.getByLabel("Send message").click()

        assertThat(page.getByRole(AriaRole.STATUS, Page.GetByRoleOptions().setName("Thinking..."))).isVisible()
        assertThat(page.getByLabel("Stop response")).isVisible()
        assertThat(page.getByRole(AriaRole.STATUS, Page.GetByRoleOptions().setName("Thinking..."))).not().isVisible()
        assertThat(page.getByText("Calculating category totals")).isVisible()
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
            page.locator("[data-chat-conversation-title]")
                .allTextContents()
                .map(String::trim)
                .shouldContainExactly(expected)
        }
        page.locator(".ai-chat-conversation-option-time").allTextContents().forEach { updatedAt ->
            updatedAt.trim().startsWith("Updated ").shouldBe(true)
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

    private fun Page.extractChartData(): List<ChartData> = locator("[data-testid='ai-chat-chart']").all().map { chart ->
        ChartData(
            title = chart.getByRole(AriaRole.HEADING).innerText(),
            kind = chart.getAttribute("data-chart-kind"),
            rows = chart.locator("tbody tr").all().map { row ->
                val cells = row.locator("td").allTextContents()
                ChartRow(cells[0], cells[1], cells[2])
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

    private data class ChartData(
        val title: String,
        val kind: String,
        val rows: List<ChartRow>,
    )

    private data class ChartRow(
        val series: String,
        val label: String,
        val value: String,
    )
}
