package io.orangebuffalo.renalo

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.tracking.*
import io.orangebuffalo.renalo.user.User
import io.orangebuffalo.renalo.user.UserType
import org.junit.jupiter.api.Test

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class ExpenseRefundApiTest : ExpenseRefundTestSupport() {
    private val endpoint = "/api/tracking/expense-refunds"

    @Test
    fun protectsBothEndpointsAndValidatesInput() {
        val f = fixture()
        users.save(User(username = "admin", passwordHash = "unused", type = UserType.ADMIN))
        val admin = tokens.issueToken("admin", UserType.ADMIN)
        val save = """{"search":${query()},"transactionId":1,"kind":"EXPENSE","fingerprint":"unknown"}"""
        listOf(null to 401, admin to 403).forEach { (token, status) ->
            api().postJson("$endpoint/search", query(), token).statusCode().shouldBe(status)
            api().postJson(endpoint, save, token).statusCode().shouldBe(status)
        }
        listOf(query(0), query(-1), query(currency = "BAD"), query(period = "BAD")).forEach {
            api().postJson("$endpoint/search", it, token(f)).statusCode().shouldBe(400)
            api().postJson(endpoint, """{"search":$it,"transactionId":1,"kind":"EXPENSE","fingerprint":"unknown"}""", token(f)).statusCode().shouldBe(400)
        }
        api().postJson("$endpoint/search", query(), token(f), "invalid/timezone").statusCode().shouldBe(400)
        api().postJson(endpoint, save, token(f), "invalid/timezone").statusCode().shouldBe(400)
    }

    @Test
    fun findsExactMatchesAcrossSameCurrencyAccountsIncludingArchivedButNotOtherUsersIncomeOrFuture() {
        val f = fixture()
        val first = expense(f)
        val archived = accounts.save(f.account.copy(id = null, name = "Old", isDefault = false, archived = true))
        val other = expense(f.copy(account = archived))
        expense(f, amount = 11999)
        expense(f, date = today.plusDays(1))
        expense(f, date = today.minusMonths(6).minusDays(1))
        expense(fixture("bob"))
        val foreign = accounts.save(f.account.copy(id = null, name = "Foreign", currency = "USD", isDefault = false))
        expense(f.copy(account = foreign))
        transactions.save(first.copy(id = null, type = TransactionType.INCOME))
        val response = api().postJson("$endpoint/search", query(), token(f))
        response.statusCode().shouldBe(200)
        val matches = mapper.readTree(response.body())
        matches.size().shouldBe(2)
        response.body().shouldEqualJson("[${expected(f, first, matches[0]["fingerprint"].asText())},${expected(f.copy(account = archived), other, matches[1]["fingerprint"].asText())}]")
        val save = saveBody(query(), matches[0])
        api().postJson(endpoint, save, token(f)).statusCode().shouldBe(204)
        transactions.findById(first.id!!).isEmpty.shouldBe(true)
        transactions.findById(other.id!!).isPresent.shouldBe(true)
        api().postJson(endpoint, save, token(f)).statusCode().shouldBe(409)
    }

    @Test
    fun periodsAreInclusiveAndDefaultToSixMonths() {
        val f = fixture()
        val dates = listOf(today.minusMonths(3), today.minusMonths(6), today.minusYears(1), today.minusYears(2), today.minusYears(3))
        val rows = dates.map { expense(f, date = it) }
        listOf("THREE_MONTHS", "SIX_MONTHS", "YEAR", "TWO_YEARS", "FULL_HISTORY").forEachIndexed { index, period ->
            val response = api().postJson("$endpoint/search", query(period = period), token(f))
            response.statusCode().shouldBe(200)
            val matches = mapper.readTree(response.body())
            response.body().shouldEqualJson(rows.take(index + 1).mapIndexed { i, row -> expected(f, row, matches[i]["fingerprint"].asText()) }.joinToString(",", "[", "]"))
        }
        val response = api().postJson("$endpoint/search", """{"currency":"AUD","amountMinor":12000}""", token(f))
        response.statusCode().shouldBe(200)
        val matches = mapper.readTree(response.body())
        response.body().shouldEqualJson(rows.take(2).mapIndexed { i, row -> expected(f, row, matches[i]["fingerprint"].asText()) }.joinToString(",", "[", "]"))
    }

    @Test
    fun sumsAllExecutedOccurrencesAndDeletesWholeSeriesIncludingLockedAndFuture() {
        val f = fixture()
        val rule = rule(f)
        val old = expense(f, 5000, today.minusYears(1), rule)
        val recent = expense(f, 7000, today, rule)
        val future = expense(f, 6000, today.plusMonths(1), rule)
        transactions.update(old.copy(recurringLocked = true))
        skips.save(RecurringTransactionSkip(recurringRuleId = rule.id!!, recurringInstanceDate = today.minusMonths(1)))
        val response = api().postJson("$endpoint/search", query(), token(f))
        response.statusCode().shouldBe(200)
        val match = mapper.readTree(response.body())[0]
        response.body().shouldEqualJson("""[{
          "transactionId":${old.id},"kind":"RECURRING_SERIES","fingerprint":${match["fingerprint"]},
          "payments":[${payment(f, old, rule)},${payment(f, recent, rule)}],"removedExpenseCount":3,"stopsRecurrence":true
        }]""")
        api().postJson("$endpoint/search", query(7000), token(f)).let {
            it.statusCode().shouldBe(200)
            val single = mapper.readTree(it.body())[0]
            it.body().shouldEqualJson("""[{"transactionId":${recent.id},"kind":"EXPENSE","fingerprint":${single["fingerprint"]},"payments":[${payment(f, recent, rule)}],"removedExpenseCount":2,"stopsRecurrence":true}]""")
        }
        api().postJson(endpoint, saveBody(query(), match), token(f)).statusCode().shouldBe(204)
        listOf(old, recent, future).forEach { transactions.findById(it.id!!).isEmpty.shouldBe(true) }
        rules.findById(rule.id!!).get().status.shouldBe(RecurringTransactionRuleStatus.DELETED)
        skips.findByRecurringRuleId(rule.id!!).shouldContainExactly(emptyList())
        generation.generateForRule(rule, today.plusYears(1))
        transactions.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!).shouldContainExactly(emptyList())
    }

    @Test
    fun singleRecurringPaymentKeepsEarlierPaymentsAndStopsFollowingOnes() {
        val f = fixture()
        val rule = rule(f)
        val old = expense(f, 5000, today.minusMonths(1), rule)
        val selected = expense(f, 12000, today, rule)
        expense(f, 6000, today.plusMonths(1), rule)
        val response = api().postJson("$endpoint/search", query(), token(f))
        response.statusCode().shouldBe(200)
        val match = mapper.readTree(response.body())[0]
        response.body().shouldEqualJson("""[{"transactionId":${selected.id},"kind":"EXPENSE","fingerprint":${match["fingerprint"]},"payments":[${payment(f, selected, rule)}],"removedExpenseCount":2,"stopsRecurrence":true}]""")
        api().postJson(endpoint, saveBody(query(), match), token(f)).statusCode().shouldBe(204)
        transactions.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!).shouldContainExactly(old)
        rules.findById(rule.id!!).get().endDate.shouldBe(today.minusDays(1))
    }

    @Test
    fun rejectsStaleOrForeignSelectionsWithoutDeletingAnything() {
        val f = fixture()
        val row = expense(f)
        val response = api().postJson("$endpoint/search", query(), token(f))
        response.statusCode().shouldBe(200)
        val match = mapper.readTree(response.body())[0]
        response.body().shouldEqualJson("[${expected(f, row, match["fingerprint"].asText())}]")
        val body = saveBody(query(), match)
        val bob = fixture("bob")
        api().postJson(endpoint, body, token(bob)).statusCode().shouldBe(409)
        transactions.update(row.copy(notes = "Changed"))
        api().postJson(endpoint, body, token(f)).statusCode().shouldBe(409)
        transactions.findById(row.id!!).isPresent.shouldBe(true)
        api().postJson("$endpoint/search", query(1), token(f)).let {
            it.statusCode().shouldBe(200)
            it.body().shouldEqualJson("[]")
        }
    }

    @Test
    fun usesBrowserLocalTodayForDiscoveryAndSaving() {
        val f = fixture()
        expense(f)
        val yesterday = expense(f, date = today.minusDays(1))
        val response = api().postJson("$endpoint/search", query(), token(f), "Pacific/Honolulu")
        response.statusCode().shouldBe(200)
        val match = mapper.readTree(response.body())[0]
        response.body().shouldEqualJson("[${expected(f, yesterday, match["fingerprint"].asText())}]")
        api().postJson(endpoint, saveBody(query(), match), token(f), "Pacific/Honolulu").statusCode().shouldBe(204)
    }

    @Test
    fun excludesMixedCurrencySeriesAndDoesNotOverflowSeriesTotals() {
        val f = fixture()
        val mixed = rule(f)
        expense(f, 6000, today.minusMonths(1), mixed)
        val foreign = accounts.save(f.account.copy(id = null, name = "Foreign", currency = "USD", isDefault = false))
        expense(f.copy(account = foreign), 6000, today, mixed)
        val huge = rule(f)
        expense(f, Long.MAX_VALUE, today.minusMonths(1), huge)
        expense(f, Long.MAX_VALUE, today, huge)
        val response = api().postJson("$endpoint/search", query(), token(f))
        response.statusCode().shouldBe(200)
        response.body().shouldEqualJson("[]")
    }

    @Test
    fun matchesWholeSeriesAcrossSameCurrencyAccounts() {
        val f = fixture()
        val secondAccount = accounts.save(f.account.copy(id = null, name = "Old", isDefault = false, archived = true))
        val second = f.copy(account = secondAccount)
        val rule = rule(f)
        val old = expense(f, 5000, today.minusYears(1), rule)
        val recent = expense(second, 7000, today, rule)
        expense(second, 6000, today.plusMonths(1), rule)
        val unrelated = expense(second, 1234)
        val response = api().postJson("$endpoint/search", query(), token(f))
        response.statusCode().shouldBe(200)
        val match = mapper.readTree(response.body())[0]
        match["fingerprint"].asText().shouldMatch("[0-9a-f]{64}")
        response.body().shouldEqualJson("""[{
          "transactionId":${old.id},"kind":"RECURRING_SERIES","fingerprint":${match["fingerprint"]},
          "payments":[${payment(f, old, rule)},${payment(second, recent, rule)}],"removedExpenseCount":3,"stopsRecurrence":true
        }]""")
        api().postJson(endpoint, saveBody(query(), match), token(f)).statusCode().shouldBe(204)
        transactions.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!).shouldContainExactly(emptyList())
        transactions.findByUserIdAndTypeOrderByDateDesc(f.user.id!!, TransactionType.EXPENSE).shouldContainExactly(unrelated)
        rules.findById(rule.id!!).get().status.shouldBe(RecurringTransactionRuleStatus.DELETED)
    }

    @Test
    fun rejectsWholeSeriesSelectionWhenAnOlderPaymentOutsideDiscoveryChanges() {
        rejectsStaleRecurringSelection { old, _, _ ->
            transactions.update(old.copy(notes = "Older payment changed"))
        }
    }

    @Test
    fun rejectsWholeSeriesSelectionWhenAFutureOccurrenceChanges() {
        rejectsStaleRecurringSelection { _, future, _ ->
            transactions.update(future.copy(amountMinor = 8000, recurringLocked = true))
        }
    }

    @Test
    fun rejectsWholeSeriesSelectionWhenTheRecurrenceRuleChanges() {
        rejectsStaleRecurringSelection { _, _, rule ->
            rules.update(rule.copy(notes = "Future schedule changed"))
        }
    }

    private fun rejectsStaleRecurringSelection(change: (Transaction, Transaction, RecurringTransactionRule) -> Unit) {
        val f = fixture()
        val rule = rule(f)
        val old = expense(f, 5000, today.minusYears(1), rule)
        val recent = expense(f, 7000, today, rule)
        val future = expense(f, 6000, today.plusMonths(1), rule)
        val response = api().postJson("$endpoint/search", query(), token(f))
        response.statusCode().shouldBe(200)
        val match = mapper.readTree(response.body())[0]
        match["fingerprint"].asText().shouldMatch("[0-9a-f]{64}")
        response.body().shouldEqualJson("""[{
          "transactionId":${old.id},"kind":"RECURRING_SERIES","fingerprint":${match["fingerprint"]},
          "payments":[${payment(f, old, rule)},${payment(f, recent, rule)}],"removedExpenseCount":3,"stopsRecurrence":true
        }]""")
        change(old, future, rule)
        val expectedRows = transactions.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!)
        val expectedRule = rules.findById(rule.id!!).get()
        api().postJson(endpoint, saveBody(query(), match), token(f)).statusCode().shouldBe(409)
        transactions.findByRecurringRuleIdOrderByRecurringInstanceDate(rule.id!!).shouldContainExactly(expectedRows)
        rules.findById(rule.id!!).get().shouldBe(expectedRule)
    }

    private fun expected(f: RefundFixture, row: Transaction, fingerprint: String): String {
        fingerprint.shouldMatch("[0-9a-f]{64}")
        return """{"transactionId":${row.id},"kind":"EXPENSE","fingerprint":"$fingerprint","payments":[${payment(f, row)}],"removedExpenseCount":1,"stopsRecurrence":false}"""
    }

    private fun payment(f: RefundFixture, row: Transaction, rule: RecurringTransactionRule? = null) = """{
        "id":${row.id},"trackingAccount":{"id":${f.account.id},"name":"${f.account.name}","currency":"${f.account.currency}"},
        "category":{"id":${f.category.id},"name":"Shopping"},"date":"${row.date}","amountMinor":${row.amountMinor},"notes":"Returned purchase"
        ${rule?.let { """, "recurrence":{"ruleId":${it.id},"startDate":"${it.startDate}","instanceDate":"${row.date}","description":"Repeats monthly"}""" } ?: ""}
    }"""
}
