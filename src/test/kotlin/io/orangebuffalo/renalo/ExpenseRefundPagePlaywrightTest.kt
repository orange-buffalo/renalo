package io.orangebuffalo.renalo

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.shouldEventually
import io.orangebuffalo.renalo.tracking.RecurringTransactionRuleStatus
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class ExpenseRefundPagePlaywrightTest : ExpenseRefundTestSupport() {
    @Test
    fun selectsWholeSeriesWithOlderPaymentsAndStopsFuturePayments(page: Page) {
        val f = fixture()
        expense(f)
        val rule = rule(f)
        expense(f, 5000, today.minusYears(1), rule)
        expense(f, 7000, today, rule)
        expense(f, 6000, today.plusMonths(1), rule)
        setStoredToken(page, token(f))
        page.navigate(server.url.toString() + "/expenses")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record refund")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Record refund"))).isVisible()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Search period").setExact(true))).containsText("Last 6 months")
        page.locator("input[name='refundAmount']").fill("120.00")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Find matches")).click()
        assertThat(page.getByRole(AriaRole.HEADING, Page.GetByRoleOptions().setName("Matching expenses (2)"))).isVisible()
        page.shouldEventually {
            page.getByRole(AriaRole.RADIOGROUP).locator("label").allTextContents().map { it.replace(Regex("\\s+"), " ").trim() }.shouldContainExactly(
                "Single expense · A$120.00$today · Shopping · Main · A$120.00 · Returned purchaseDeletes 1 expense.",
                "Whole recurring series · A$120.00${today.minusYears(1)} · Shopping · Main · A$50.00 · Returned purchaseRepeats monthly$today · Shopping · Main · A$70.00 · Returned purchaseRepeats monthlyDeletes 3 expenses and stops future payments.",
            )
        }
        page.getByRole(AriaRole.RADIOGROUP).locator("label").nth(1).locator("p").first().click()
        assertThat(page.getByRole(AriaRole.ALERT)).containsText("3 expenses will be permanently deleted.")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record refund")).click()
        assertThat(page).hasURL(server.url.toString() + "/expenses")
        transactions.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!).shouldContainExactly(emptyList())
        rules.findById(rule.id!!).get().status.shouldBe(RecurringTransactionRuleStatus.DELETED)
    }

    @Test
    fun changesPeriodAndCurrencyClearsSelectionAndHandlesNoMatchesAndCancel(page: Page) {
        val f = fixture()
        expense(f, date = today.minusYears(1))
        accounts.save(f.account.copy(id = null, name = "Travel", currency = "EUR", isDefault = false))
        setStoredToken(page, token(f))
        page.navigate(server.url.toString() + "/expenses/refund")
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Currency").setExact(true))).containsText("AUD")
        page.locator("input[name='refundAmount']").fill("120.00")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Find matches")).click()
        assertThat(page.getByText("No matching expenses. Try a longer period or check the amount and currency.")).isVisible()
        choose(page, "Search period", "Last year")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Find matches")).click()
        assertThat(page.getByRole(AriaRole.RADIO)).hasCount(1)
        page.getByRole(AriaRole.RADIOGROUP).locator("label p").first().click()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record refund"))).isEnabled()
        choose(page, "Currency", "EUR")
        assertThat(page.getByRole(AriaRole.RADIO)).hasCount(0)
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record refund"))).isDisabled()
        assertThat(page.locator("input[name='refundAmount']")).isEmpty()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Cancel")).click()
        assertThat(page).hasURL(server.url.toString() + "/expenses")
        transactions.count().shouldBe(1)
    }

    @Test
    fun requiresSearchingAgainWhenAnExpenseChanges(page: Page) {
        val f = fixture()
        val row = expense(f)
        setStoredToken(page, token(f))
        page.navigate(server.url.toString() + "/expenses/refund")
        page.locator("input[name='refundAmount']").fill("120")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Find matches")).click()
        page.getByRole(AriaRole.RADIOGROUP).locator("label p").first().click()
        page.locator("input[name='refundAmount']").fill("120")
        page.locator("input[name='refundAmount']").press("Enter")
        page.getByRole(AriaRole.RADIOGROUP).locator("label p").first().click()
        transactions.update(row.copy(amountMinor = 13000))
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record refund")).click()
        assertThat(page.getByRole(AriaRole.ALERT)).hasText("These expenses have changed. Find matches again before recording the refund.")
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record refund"))).isDisabled()
        transactions.findById(row.id!!).isPresent.shouldBe(true)
    }

    @Test
    fun ignoresADelayedSearchResponseAfterANewerSearchCompletes(page: Page) {
        val f = fixture()
        expense(f)
        expense(f, 13000)
        var delayedRoute: Route? = null
        var delayedBody: String? = null
        page.route("**/api/tracking/expense-refunds/search") { route ->
            if (route.request().postData()!!.contains("\"amountMinor\":12000")) {
                delayedBody = route.fetch().text()
                delayedRoute = route
            } else route.resume()
        }
        setStoredToken(page, token(f))
        page.navigate(server.url.toString() + "/expenses/refund")
        page.locator("input[name='refundAmount']").fill("120")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Find matches")).click()
        page.waitForCondition { delayedRoute != null }
        page.locator("input[name='refundAmount']").fill("130")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Find matches")).click()
        assertSingleExpenseMatch(page, "130.00")
        page.getByRole(AriaRole.RADIOGROUP).locator("label p").first().click()
        page.waitForResponse({ response -> response.url().endsWith("/expense-refunds/search") }) {
            checkNotNull(delayedRoute).fulfill(
                Route.FulfillOptions().setStatus(200).setContentType("application/json").setBody(checkNotNull(delayedBody)),
            )
        }.finished()
        // Let the stale response's promise callbacks and any resulting React render finish.
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
        assertSingleExpenseMatch(page, "130.00")
        assertThat(page.getByRole(AriaRole.RADIO)).isChecked()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record refund"))).isEnabled()
        transactions.count().shouldBe(2)
    }

    @Test
    fun showsGenericSearchFailureAndAllowsRetry(page: Page) {
        val f = fixture()
        expense(f)
        var failSearch = true
        page.route("**/api/tracking/expense-refunds/search") { route ->
            if (failSearch) route.fulfill(Route.FulfillOptions().setStatus(500)) else route.resume()
        }
        setStoredToken(page, token(f))
        page.navigate(server.url.toString() + "/expenses/refund")
        page.locator("input[name='refundAmount']").fill("120")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Find matches")).click()
        assertThat(page.getByRole(AriaRole.ALERT)).hasText("Could not find matching expenses. Please try again.")
        assertThat(page.getByRole(AriaRole.RADIO)).hasCount(0)
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record refund"))).isDisabled()
        failSearch = false
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Find matches")).click()
        assertSingleExpenseMatch(page, "120.00")
        assertThat(page.getByRole(AriaRole.ALERT)).hasCount(0)
        transactions.count().shouldBe(1)
    }

    @Test
    fun showsGenericSaveFailureAndPreservesSelectionForRetry(page: Page) {
        val f = fixture()
        val row = expense(f)
        var failSave = true
        page.route("**/api/tracking/expense-refunds") { route ->
            if (failSave) route.fulfill(Route.FulfillOptions().setStatus(500)) else route.resume()
        }
        setStoredToken(page, token(f))
        page.navigate(server.url.toString() + "/expenses/refund")
        page.locator("input[name='refundAmount']").fill("120")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Find matches")).click()
        assertSingleExpenseMatch(page, "120.00")
        page.getByRole(AriaRole.RADIOGROUP).locator("label p").first().click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record refund")).click()
        page.shouldEventually {
            page.getByRole(AriaRole.ALERT).allTextContents().map { it.replace(Regex("\\s+"), " ").trim() }.shouldContainExactly(
                "Could not record the refund. Please try again.",
                "This cannot be undone1 expense will be permanently deleted. No income entry will be created.",
            )
        }
        assertThat(page).hasURL(server.url.toString() + "/expenses/refund")
        assertSingleExpenseMatch(page, "120.00")
        assertThat(page.getByRole(AriaRole.RADIO)).isChecked()
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record refund"))).isEnabled()
        transactions.findById(row.id!!).get().shouldBe(row)
        failSave = false
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Record refund")).click()
        assertThat(page).hasURL(server.url.toString() + "/expenses")
        transactions.count().shouldBe(0)
    }

    private fun assertSingleExpenseMatch(page: Page, amount: String) {
        page.shouldEventually {
            page.getByRole(AriaRole.RADIOGROUP).locator("label").allTextContents().map { it.replace(Regex("\\s+"), " ").trim() }.shouldContainExactly(
                "Single expense · A$$amount$today · Shopping · Main · A$$amount · Returned purchaseDeletes 1 expense.",
            )
        }
    }

    private fun choose(page: Page, label: String, value: String) {
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(label).setExact(true)).click()
        page.locator(".searchable-dropdown-popover").getByText(value, com.microsoft.playwright.Locator.GetByTextOptions().setExact(true)).click()
    }
}
