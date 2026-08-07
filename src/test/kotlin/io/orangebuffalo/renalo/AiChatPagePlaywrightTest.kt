package io.orangebuffalo.renalo

import com.fasterxml.jackson.databind.ObjectMapper
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
@Property(name = "renalo.ai-chat.litellm.max-context-tokens", value = "150")
class AiChatPagePlaywrightTest : IntegrationTestSupport() {
    private val objectMapper = ObjectMapper()

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
                    rows = listOf(ChartRow("Expenses", "Groceries", "A${'$'}23.45")),
                ),
            )
        }
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Spending snapshot"))).isVisible()
        assertThat(page.getByText("Calculated category totals, Prepared chart")).isVisible()
        val turnMetrics = page.locator(".ai-chat-turn-metrics").innerText()
        Regex("(?:\\d+\\.\\d+s|\\d+s|\\d+m \\d+s) · 360 tokens").matches(turnMetrics).shouldBe(true)
        val contextIndicator = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName(
                "Context 80% full. Current size: 120 tokens. Maximum size: 150 tokens. Context is filling up. Start a new chat soon, as this chat might fail once the context is full.",
            ),
        )
        assertThat(contextIndicator).isVisible()
        contextIndicator.click()
        assertThat(page.getByText("Context 80% full")).isVisible()
        assertThat(
            page.getByText(
                "Current size: 120 tokens. Maximum size: 150 tokens. Context is filling up. Start a new chat soon, as this chat might fail once the context is full.",
            ),
        ).isVisible()

        page.reload()
        page.shouldEventually {
            extractChartData().shouldContainExactly(
                ChartData(
                    title = "Expenses by category",
                    kind = "DONUT",
                    rows = listOf(ChartRow("Expenses", "Groceries", "A${'$'}23.45")),
                ),
            )
        }
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Spending snapshot"))).isVisible()
        page.locator(".ai-chat-turn-metrics").innerText().shouldBe(turnMetrics)
        assertThat(contextIndicator).isVisible()
        val historicalActivity = page.getByText("Calculated category totals, Prepared chart")
        assertThat(historicalActivity).isVisible()
        historicalActivity.click()
    }

    @Test
    fun rendersFlexibleMultiSeriesChartsFromSavedHistory(page: Page) {
        val user = saveUser("alice", UserType.USER)
        val conversation = conversationRepository.save(AiChatConversation(userId = user.id!!, title = "Chart gallery"))
        val chartValues = listOf(
            charts.create(
                objectMapper.readTree(
                    """
                        {"kind":"LINE","title":"Balance trend","xAxisLabel":"Month","xAxisType":"DATE","yAxisLabel":"Balance","yAxisType":"MONEY_MINOR","currency":"AUD","stacked":false,"orientation":"VERTICAL","series":[
                          {"name":"Daily","points":[{"x":"2026-01-01","y":"1000"},{"x":"2026-02-01","y":"2500"}]},
                          {"name":"Savings","points":[{"x":"2026-01-01","y":"5000"},{"x":"2026-02-01","y":"6200"}]}
                        ]}
                    """.trimIndent(),
                ),
                "00000000-0000-0000-0000-000000000101",
            ),
            charts.create(
                objectMapper.readTree(
                    """
                        {"kind":"BAR","title":"Spending by account and category","xAxisLabel":"Account","xAxisType":"CATEGORY","yAxisLabel":"Expenses","yAxisType":"MONEY_MINOR","currency":"AUD","stacked":true,"orientation":"HORIZONTAL","series":[
                          {"name":"Food","points":[{"x":"Account 1","y":"100"},{"x":"Account 2","y":"200"},{"x":"Account 3","y":"300"},{"x":"Account 4","y":"400"},{"x":"Account 5","y":"500"},{"x":"Account 6","y":"600"},{"x":"Account 7","y":"700"},{"x":"Account 8","y":"800"},{"x":"Account 9","y":"900"},{"x":"Account 10","y":"1000"}]},
                          {"name":"Rent","points":[{"x":"Account 1","y":"1000"},{"x":"Account 2","y":"2000"},{"x":"Account 3","y":"3000"},{"x":"Account 4","y":"4000"},{"x":"Account 5","y":"5000"},{"x":"Account 6","y":"6000"},{"x":"Account 7","y":"7000"},{"x":"Account 8","y":"8000"},{"x":"Account 9","y":"9000"},{"x":"Account 10","y":"10000"}]}
                        ]}
                    """.trimIndent(),
                ),
                "00000000-0000-0000-0000-000000000102",
            ),
            charts.create(
                objectMapper.readTree(
                    """
                        {"kind":"DONUT","title":"Income share","xAxisLabel":"Source","xAxisType":"CATEGORY","yAxisLabel":"Income","yAxisType":"MONEY_MINOR","currency":"AUD","stacked":false,"orientation":"VERTICAL","series":[
                          {"name":"Income","points":[{"x":"Salary","y":"9000"},{"x":"Interest","y":"1000"}]}
                        ]}
                    """.trimIndent(),
                ),
                "00000000-0000-0000-0000-000000000103",
            ),
            charts.create(
                objectMapper.readTree(
                    """
                        {"kind":"AREA","title":"Savings growth","xAxisLabel":"Quarter","xAxisType":"CATEGORY","yAxisLabel":"Growth rate","yAxisType":"NUMBER","currency":"","stacked":false,"orientation":"VERTICAL","series":[
                          {"name":"Growth","points":[{"x":"Q1","y":"1.25"},{"x":"Q2","y":"2.5"}]}
                        ]}
                    """.trimIndent(),
                ),
                "00000000-0000-0000-0000-000000000104",
            ),
            charts.create(
                objectMapper.readTree(
                    """
                        {"kind":"PIE","title":"Expense share","xAxisLabel":"Category","xAxisType":"CATEGORY","yAxisLabel":"Expenses","yAxisType":"MONEY_MINOR","currency":"AUD","stacked":false,"orientation":"VERTICAL","series":[
                          {"name":"Expenses","points":[{"x":"Food","y":"1200"},{"x":"Rent","y":"8800"}]}
                        ]}
                    """.trimIndent(),
                ),
                "00000000-0000-0000-0000-000000000105",
            ),
            charts.create(
                objectMapper.readTree(
                    """
                        {"kind":"SCATTER","title":"Transaction size and frequency","xAxisLabel":"Transactions","xAxisType":"NUMBER","yAxisLabel":"Average amount","yAxisType":"MONEY_MINOR","currency":"AUD","stacked":false,"orientation":"VERTICAL","series":[
                          {"name":"Accounts","points":[{"x":"3","y":"2500"},{"x":"8","y":"1200"}]}
                        ]}
                    """.trimIndent(),
                ),
                "00000000-0000-0000-0000-000000000106",
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
                        ChartRow("Daily", "2026-01-01", "A${'$'}10.00"),
                        ChartRow("Daily", "2026-02-01", "A${'$'}25.00"),
                        ChartRow("Savings", "2026-01-01", "A${'$'}50.00"),
                        ChartRow("Savings", "2026-02-01", "A${'$'}62.00"),
                    ),
                ),
                ChartData(
                    "Spending by account and category",
                    "BAR",
                    (1..10).map { ChartRow("Food", "Account $it", "A${'$'}${it}.00") } +
                        (1..10).map { ChartRow("Rent", "Account $it", "A${'$'}${it * 10}.00") },
                ),
                ChartData(
                    "Income share",
                    "DONUT",
                    listOf(
                        ChartRow("Income", "Salary", "A${'$'}90.00"),
                        ChartRow("Income", "Interest", "A${'$'}10.00"),
                    ),
                ),
                ChartData(
                    "Savings growth",
                    "AREA",
                    listOf(ChartRow("Growth", "Q1", "1.25"), ChartRow("Growth", "Q2", "2.5")),
                ),
                ChartData(
                    "Expense share",
                    "PIE",
                    listOf(
                        ChartRow("Expenses", "Food", "A${'$'}12.00"),
                        ChartRow("Expenses", "Rent", "A${'$'}88.00"),
                    ),
                ),
                ChartData(
                    "Transaction size and frequency",
                    "SCATTER",
                    listOf(
                        ChartRow("Accounts", "3", "A${'$'}25.00"),
                        ChartRow("Accounts", "8", "A${'$'}12.00"),
                    ),
                ),
            )
        }
        listOf("line", "bar", "donut", "area", "pie", "scatter").forEach { kind ->
            val chart = page.locator("[data-testid='ai-chat-$kind-chart']")
            chart.scrollIntoViewIfNeeded()
            assertThat(chart).isVisible()
        }
        val lineHeight = page.getByTestId("ai-chat-line-chart").boundingBox().height
        val barHeight = page.getByTestId("ai-chat-bar-chart").boundingBox().height
        (barHeight > lineHeight).shouldBe(true)

        page.getByLabel("Maximize Balance trend chart").click()
        val chartDialog = page.getByRole(AriaRole.DIALOG, Page.GetByRoleOptions().setName("Balance trend chart"))
        assertThat(chartDialog).isVisible()
        chartDialog.locator("[data-testid='ai-chat-chart']").extractChartData().shouldBe(
            ChartData(
                "Balance trend",
                "LINE",
                listOf(
                    ChartRow("Daily", "2026-01-01", "A${'$'}10.00"),
                    ChartRow("Daily", "2026-02-01", "A${'$'}25.00"),
                    ChartRow("Savings", "2026-01-01", "A${'$'}50.00"),
                    ChartRow("Savings", "2026-02-01", "A${'$'}62.00"),
                ),
            ),
        )
        chartDialog.getByLabel("Close Balance trend chart").click()
        assertThat(chartDialog).not().isVisible()

        page.setViewportSize(390, 844)
        val chartMessage = page.locator(".ai-chat-message--has-chart")
        val feed = page.locator(".ai-chat-feed")
        val feedContentWidth = (feed.evaluate(
            "feed => feed.clientWidth - parseFloat(getComputedStyle(feed).paddingLeft) - parseFloat(getComputedStyle(feed).paddingRight)",
        ) as Number).toDouble()
        val widthDifference = feedContentWidth - chartMessage.boundingBox().width
        (kotlin.math.abs(widthDifference) < 1).shouldBe(true)
        page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Balance trend")).click()
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
                    listOf(ToolActivity("Calculated category totals", "COMPLETED")),
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
    fun persistsATopicChangeRecommendationAndContinuesInTheSameChat(page: Page) {
        val user = saveUser("alice", UserType.USER)
        saveEstablishedConversation(user.id!!, "Focused chat")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.navigate(server.url.toString() + "/chat")
        assertThat(page.getByText("Original focused answer", Page.GetByTextOptions().setExact(true))).isVisible()

        val messageInput = page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        )
        messageInput.fill("Change topic to retirement planning")
        page.getByLabel("Send message").click()

        val recommendation = page.getByRole(AriaRole.ALERT)
        assertThat(recommendation).containsText("This looks like a different topic")
        assertThat(recommendation).containsText(
            "Focused, shorter chats use less context and are less likely to fail as the conversation grows.",
        )
        assertThat(recommendation.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Continue in a new chat")))
            .isVisible()
        assertThat(recommendation.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Continue here")))
            .isVisible()
        assertThat(messageInput).isDisabled()

        page.reload()
        assertThat(page.getByText("Original focused answer", Page.GetByTextOptions().setExact(true))).isVisible()
        assertThat(recommendation).isVisible()
        recommendation.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Continue here")).click()

        assertThat(recommendation).not().isVisible()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Spending snapshot"))).isVisible()
        assertThat(page.getByText("You asked: Change topic to retirement planning", Page.GetByTextOptions().setExact(true)))
            .isVisible()
        page.reload()
        assertThat(recommendation).not().isVisible()
        assertThat(page.locator("[data-chat-author='You']").getByText("Change topic to retirement planning", Locator.GetByTextOptions().setExact(true)))
            .isVisible()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Spending snapshot"))).isVisible()
    }

    @Test
    fun movesATopicChangeToANewChatAndKeepsTheOldChatFocused(page: Page) {
        val user = saveUser("alice", UserType.USER)
        saveEstablishedConversation(user.id!!, "Focused chat")
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.navigate(server.url.toString() + "/chat")
        assertThat(page.getByText("Original focused answer", Page.GetByTextOptions().setExact(true))).isVisible()

        val prompt = "Change topic and show my spend by month"
        val messageInput = page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        )
        messageInput.fill(prompt)
        page.getByLabel("Send message").click()
        val recommendation = page.getByRole(AriaRole.ALERT)
        assertThat(recommendation).isVisible()
        recommendation.getByRole(
            AriaRole.BUTTON,
            Locator.GetByRoleOptions().setName("Continue in a new chat"),
        ).click()

        page.shouldEventually {
            conversationSelector(page).textContent().shouldBe("Monthly spending review")
        }
        assertThat(page.locator("[data-chat-author='You']").getByText(prompt, Locator.GetByTextOptions().setExact(true)))
            .isVisible()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Spending snapshot"))).isVisible()
        assertConversationOptions(page, listOf("Monthly spending review", "Focused chat", "New chat"))

        conversationSelector(page).click()
        page.getByRole(AriaRole.MENUITEMRADIO, Page.GetByRoleOptions().setName("Focused chat")).click()
        page.shouldEventually {
            extractMessages().shouldContainExactly(
                ChatMessage("You", "Original focused question", emptyList()),
                ChatMessage("Renalo", "Original focused answer", emptyList()),
            )
        }
        assertThat(page.locator("[data-chat-author='You']").getByText(prompt, Locator.GetByTextOptions().setExact(true)))
            .not().isVisible()
        assertThat(messageInput).isEnabled()
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
        page.locator(".ai-chat-panel").boundingBox().let { panel ->
            panel.x.shouldBe(0.0)
            panel.width.shouldBe(390.0)
            (panel.y + panel.height).shouldBe(844.0)
        }
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
    fun showsReviewActivityBetweenToolExecutionAndTheResponse(page: Page) {
        saveUser("alice", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.navigate(server.url.toString() + "/chat")

        page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        ).fill("Slow review request")
        page.getByLabel("Send message").click()

        assertThat(page.getByRole(AriaRole.STATUS, Page.GetByRoleOptions().setName("Reviewing results..."))).isVisible()
        assertThat(page.getByText("Calculated category totals")).isVisible()
        assertThat(page.getByRole(AriaRole.STATUS, Page.GetByRoleOptions().setName("Reviewing results..."))).not().isVisible()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Spending snapshot"))).isVisible()
    }

    @Test
    fun summarizesToolCallsBetweenAssistantOutput(page: Page) {
        saveUser("alice", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.route("**/api/ai-chat/messages") { route ->
            route.fulfill(
                Route.FulfillOptions()
                    .setContentType("application/x-ndjson")
                    .setBody(
                        """
                            {"v":1,"seq":1,"type":"turn.started"}
                            {"v":1,"seq":2,"type":"assistant.delta","text":"I checked the broad spending history."}
                            {"v":1,"seq":3,"type":"tool.started","activityId":"search-1","label":"Searching transactions"}
                            {"v":1,"seq":4,"type":"tool.completed","activityId":"search-1","label":"Searched transactions","status":"COMPLETED"}
                            {"v":1,"seq":5,"type":"tool.started","activityId":"search-2","label":"Searching transactions"}
                            {"v":1,"seq":6,"type":"tool.completed","activityId":"search-2","label":"Searched transactions","status":"COMPLETED"}
                            {"v":1,"seq":7,"type":"tool.started","activityId":"search-3","label":"Searching transactions"}
                            {"v":1,"seq":8,"type":"tool.completed","activityId":"search-3","label":"Searched transactions","status":"COMPLETED"}
                            {"v":1,"seq":9,"type":"tool.started","activityId":"categories-1","label":"Calculating category totals"}
                            {"v":1,"seq":10,"type":"tool.completed","activityId":"categories-1","label":"Calculated category totals","status":"COMPLETED"}
                            {"v":1,"seq":11,"type":"tool.started","activityId":"categories-2","label":"Calculating category totals"}
                            {"v":1,"seq":12,"type":"tool.completed","activityId":"categories-2","label":"Calculated category totals","status":"COMPLETED"}
                            {"v":1,"seq":13,"type":"assistant.thinking","label":"Reviewing results"}
                            {"v":1,"seq":14,"type":"assistant.delta","text":"I then narrowed the date range."}
                            {"v":1,"seq":15,"type":"tool.started","activityId":"search-4","label":"Searching transactions"}
                            {"v":1,"seq":16,"type":"tool.completed","activityId":"search-4","label":"Searched transactions","status":"COMPLETED"}
                            {"v":1,"seq":17,"type":"assistant.thinking","label":"Reviewing results"}
                            {"v":1,"seq":18,"type":"assistant.delta","text":"Leisure spending increased overall."}
                            {"v":1,"seq":19,"type":"turn.completed"}
                        """.trimIndent() + "\n",
                    ),
            )
        }
        page.navigate(server.url.toString() + "/chat")

        page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        ).fill("Summarize the tool activity")
        page.getByLabel("Send message").click()

        page.shouldEventually {
            page.locator("[data-chat-author='Renalo'] .ai-chat-tool-activity").allInnerTexts()
                .shouldContainExactly(
                    "Searched transactions (3), Calculated category totals (2)",
                    "Searched transactions",
                )
            page.locator(
                "[data-chat-author='Renalo'] .ai-chat-markdown, " +
                    "[data-chat-author='Renalo'] .ai-chat-tool-activity",
            ).allInnerTexts().map { it.trim() }.shouldContainExactly(
                "I checked the broad spending history.",
                "Searched transactions (3), Calculated category totals (2)",
                "I then narrowed the date range.",
                "Searched transactions",
                "Leisure spending increased overall.",
            )
        }
        page.getByText("Leisure spending increased overall.").click()
    }

    @Test
    fun showsContextSizeWithoutAConfiguredMaximum(page: Page) {
        saveUser("alice", UserType.USER)
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER))
        page.route("**/api/ai-chat/messages") { route ->
            route.fulfill(
                Route.FulfillOptions()
                    .setContentType("application/x-ndjson")
                    .setBody(
                        """
                            {"v":1,"seq":1,"type":"turn.started"}
                            {"v":1,"seq":2,"type":"assistant.delta","text":"Context is available."}
                            {"v":1,"seq":3,"type":"turn.completed","metrics":{"durationMillis":1500},"contextUsage":{"currentTokens":120}}
                        """.trimIndent() + "\n",
                    ),
            )
        }
        page.navigate(server.url.toString() + "/chat")

        page.getByRole(
            AriaRole.TEXTBOX,
            Page.GetByRoleOptions().setName("Message").setExact(true),
        ).fill("Show context usage")
        page.getByLabel("Send message").click()

        assertThat(page.getByText("Context is available.")).isVisible()
        assertThat(page.getByText("2s · Token usage unavailable")).isVisible()
        val contextIndicator = page.getByRole(
            AriaRole.BUTTON,
            Page.GetByRoleOptions().setName(
                "Context usage. Current size: 120 tokens. Maximum size: Unavailable. The context limit is unknown, so this chat could fail unexpectedly if it overflows. Keep this chat focused and short.",
            ),
        )
        assertThat(contextIndicator).isVisible()
        contextIndicator.locator(".ai-chat-context-progress-value").count().shouldBe(0)
        contextIndicator.click()
        assertThat(page.getByText("Context usage", Page.GetByTextOptions().setExact(true))).isVisible()
        assertThat(
            page.getByText(
                "Current size: 120 tokens. Maximum size: Unavailable. The context limit is unknown, so this chat could fail unexpectedly if it overflows. Keep this chat focused and short.",
            ),
        ).isVisible()
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
        val author = message.getAttribute("data-chat-author")
        ChatMessage(
            author = author,
            content = if (author == "Renalo") {
                message.locator(".ai-chat-markdown").allInnerTexts().joinToString("\n\n")
            } else {
                message.locator(".ai-chat-message-content").innerText()
            },
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

    private fun Locator.extractChartData(): ChartData = ChartData(
        title = getByRole(AriaRole.HEADING).innerText(),
        kind = getAttribute("data-chart-kind"),
        rows = locator("tbody tr").all().map { row ->
            val cells = row.locator("td").allTextContents()
            ChartRow(cells[0], cells[1], cells[2])
        },
    )

    private fun saveUser(username: String, type: UserType): User = userRepository.save(
        User(
            username = username,
            passwordHash = passwordHasher.hash("password"),
            type = type,
        ),
    )

    private fun saveEstablishedConversation(userId: Long, title: String) {
        val conversation = conversationRepository.save(AiChatConversation(userId = userId, title = title))
        conversationEventService.appendItems(
            userId,
            conversation.id!!,
            listOf(
                conversationEventService.userMessage("Original focused question"),
                """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Original focused answer"}]}""",
            ),
        )
    }

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
