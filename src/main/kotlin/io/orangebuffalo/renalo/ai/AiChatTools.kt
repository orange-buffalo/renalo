package io.orangebuffalo.renalo.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import io.orangebuffalo.renalo.tracking.DashboardService
import io.orangebuffalo.renalo.tracking.DefaultCurrencyConversionSource
import io.orangebuffalo.renalo.tracking.FundsTransferDateFilter
import io.orangebuffalo.renalo.tracking.FundsTransferService
import io.orangebuffalo.renalo.tracking.NetWorthAnalyticsService
import io.orangebuffalo.renalo.tracking.TransactionDateFilter
import io.orangebuffalo.renalo.tracking.TransactionService
import io.orangebuffalo.renalo.tracking.TransactionQueryOrder
import io.orangebuffalo.renalo.tracking.TransactionQueryCriteria
import io.orangebuffalo.renalo.tracking.TransactionTimeSeriesGranularity
import io.orangebuffalo.renalo.tracking.TransactionType
import io.orangebuffalo.renalo.tracking.SortDirection
import jakarta.inject.Singleton
import java.time.LocalDate

@Singleton
class AiChatTools(
    private val dashboardService: DashboardService,
    private val transactionService: TransactionService,
    private val netWorthAnalyticsService: NetWorthAnalyticsService,
    private val fundsTransferService: FundsTransferService,
    private val charts: AiChatCharts,
) {
    private val objectMapper = ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    val specifications: List<ToolSpecification> = listOf(
        tool(
            name = ACCOUNT_BALANCES,
            description = "Get current balances and current-month inflow/outflow for all active accounts. Amounts are exact minor units in each account currency.",
            parameters = parameters(),
        ),
        tool(
            name = CATEGORY_TOTALS,
            description = "Get expense or income totals grouped by category in the default currency for an inclusive date range.",
            parameters = parameters(
                "type" to "EXPENSE or INCOME",
                "from" to "Inclusive date in YYYY-MM-DD format",
                "to" to "Inclusive date in YYYY-MM-DD format",
            ),
        ),
        tool(
            name = TRANSACTION_SEARCH,
            description = "Query the complete user transaction set with broad filters, arbitrary whitelisted ordering, pagination, and complete-set monetary summaries. All amounts are integer minor units. Empty strings mean no filter.",
            parameters = parameters(
                "type" to "EXPENSE or INCOME",
                "from" to "Inclusive date in YYYY-MM-DD format, or an empty string for no lower bound",
                "to" to "Inclusive date in YYYY-MM-DD format, or an empty string for no upper bound",
                "transactionIds" to "Comma-separated transaction IDs, or empty",
                "categoryIds" to "Comma-separated category IDs, or empty",
                "categoryNameQuery" to "Case-insensitive category-name text, or empty",
                "accountIds" to "Comma-separated account IDs, or empty",
                "accountNameQuery" to "Case-insensitive account-name text, or empty",
                "notesQuery" to "Notes, description, or payee words that must all match, or empty",
                "currencies" to "Comma-separated original account currencies, or empty",
                "minAmountMinor" to "Minimum original amount minor units, or empty",
                "maxAmountMinor" to "Maximum original amount minor units, or empty",
                "defaultCurrencies" to "Comma-separated projected currencies, or empty",
                "minDefaultCurrencyAmountMinor" to "Minimum projected amount minor units, or empty",
                "maxDefaultCurrencyAmountMinor" to "Maximum projected amount minor units, or empty",
                "conversionSources" to "Comma-separated SAME_CURRENCY, ACTUAL_TRANSFER, or UNAVAILABLE values, or empty",
                "conversionTransferIds" to "Comma-separated conversion evidence transfer IDs, or empty",
                "recurring" to "ANY, TRUE, or FALSE",
                "recurringRuleIds" to "Comma-separated recurring rule IDs, or empty",
                "recurringInstanceFrom" to "Minimum recurring instance date in YYYY-MM-DD format, or empty",
                "recurringInstanceTo" to "Maximum recurring instance date in YYYY-MM-DD format, or empty",
                "recurringLocked" to "ANY, TRUE, or FALSE",
                "metadataSources" to "Comma-separated metadata source values such as toshl, or empty",
                "orderBy" to "ID, ACCOUNT_ID, CATEGORY_ID, DATE, AMOUNT, CURRENCY, DEFAULT_CURRENCY_AMOUNT, DEFAULT_CURRENCY, CONVERSION_SOURCE, CONVERSION_TRANSFER_ID, NOTES, ACCOUNT_NAME, CATEGORY_NAME, RECURRING_RULE_ID, RECURRING_INSTANCE_DATE, RECURRING_LOCKED, or METADATA_SOURCE",
                "direction" to "ASC or DESC",
                "offset" to "Zero-based result offset",
                "limit" to "Page size from 1 to 50",
            ),
        ),
        tool(
            name = TRANSACTION_TIME_SERIES,
            description = "Get expense or income totals by calendar bucket in the current default currency for a line chart or time-based analysis.",
            parameters = parameters(
                "type" to "EXPENSE or INCOME",
                "from" to "Inclusive date in YYYY-MM-DD format",
                "to" to "Inclusive date in YYYY-MM-DD format",
                "granularity" to "AUTO, DAY, WEEK, or MONTH",
            ),
        ),
        tool(
            name = NET_WORTH,
            description = "Get cumulative net worth in the default currency over an inclusive date range through today.",
            parameters = parameters(
                "from" to "Inclusive date in YYYY-MM-DD format",
                "to" to "Inclusive date in YYYY-MM-DD format; must not be after today",
                "granularity" to "AUTO, DAY, WEEK, or MONTH",
            ),
        ),
        tool(
            name = TRANSFER_SEARCH,
            description = "Search funds transfers in an inclusive date range. Returns at most 50 newest matches.",
            parameters = parameters(
                "from" to "Inclusive date in YYYY-MM-DD format",
                "to" to "Inclusive date in YYYY-MM-DD format",
            ),
        ),
        tool(
            name = AiChatCharts.PRESENT_CHART_TOOL,
            description = "Present a prior compatible data-tool result as a chart. Prefer a chart whenever it can answer or materially clarify the user's question. LINE requires time-series or net-worth data; PIE and DONUT require category totals.",
            parameters = parameters(
                "kind" to "LINE, PIE, or DONUT",
                "title" to "Concise plain-text chart title, 1 to 100 characters",
            ),
        ),
    )

    fun activity(call: AiChatModelToolCall): Pair<String, String> = when (call.name) {
        ACCOUNT_BALANCES -> "Reviewing account balances" to "Reviewed account balances"
        CATEGORY_TOTALS -> "Calculating category totals" to "Calculated category totals"
        TRANSACTION_SEARCH -> "Searching transactions" to "Searched transactions"
        TRANSACTION_TIME_SERIES -> "Calculating transaction trend" to "Calculated transaction trend"
        NET_WORTH -> "Calculating net worth" to "Calculated net worth"
        TRANSFER_SEARCH -> "Searching transfers" to "Searched transfers"
        AiChatCharts.PRESENT_CHART_TOOL -> "Preparing chart" to "Prepared chart"
        else -> "Reviewing financial data" to "Reviewed financial data"
    }

    fun execute(
        userId: Long,
        currentDate: LocalDate,
        call: AiChatModelToolCall,
        context: AiChatToolExecutionContext = AiChatToolExecutionContext(),
    ): ExecutedAiChatTool {
        val arguments = objectMapper.readTree(call.arguments)
        return when (call.name) {
            ACCOUNT_BALANCES -> ExecutedAiChatTool(
                activityId = call.id,
                startedLabel = "Reviewing account balances",
                completedLabel = "Reviewed account balances",
                result = objectMapper.writeValueAsString(dashboardService.getAccountSummaries(userId, currentDate)),
            )
            CATEGORY_TOTALS -> {
                val type = arguments.transactionType()
                val range = arguments.dateRange()
                val totals = transactionService.getCategoryTotals(
                    userId,
                    type,
                    TransactionDateFilter(range.first, range.second),
                )
                ExecutedAiChatTool(
                    call.id,
                    "Calculating category totals",
                    "Calculated category totals",
                    objectMapper.writeValueAsString(totals),
                    chartSource = totals.firstOrNull()?.let { first ->
                        AiChatChartSource.Slices(
                            currency = first.currency,
                            segments = totals.map { AiChatChartSourceSegment(it.categoryName, it.amountMinor) },
                        )
                    },
                )
            }
            TRANSACTION_TIME_SERIES -> {
                val type = arguments.transactionType()
                val range = arguments.dateRange()
                val granularity = enumValue<TransactionTimeSeriesGranularity>(arguments.requiredText("granularity"))
                val timeSeries = transactionService.getTimeSeries(
                    userId,
                    type,
                    TransactionDateFilter(range.first, range.second),
                    granularity,
                )
                ExecutedAiChatTool(
                    call.id,
                    "Calculating transaction trend",
                    "Calculated transaction trend",
                    objectMapper.writeValueAsString(timeSeries),
                    chartSource = timeSeries.points.firstOrNull()?.let { first ->
                        AiChatChartSource.Line(
                            currency = first.currency,
                            seriesName = if (type == TransactionType.EXPENSE) "Expenses" else "Income",
                            points = timeSeries.points.map { AiChatChartSourcePoint(it.bucket, it.amountMinor) },
                        )
                    },
                )
            }
            TRANSACTION_SEARCH -> {
                val type = arguments.transactionType()
                val from = arguments.optionalDate("from")
                val to = arguments.optionalDate("to")
                require(from == null || to == null || !from.isAfter(to)) { "from must not be after to" }
                val amountMinorFrom = arguments.optionalLong("minAmountMinor")
                val amountMinorTo = arguments.optionalLong("maxAmountMinor")
                require(amountMinorFrom == null || amountMinorTo == null || amountMinorFrom <= amountMinorTo) {
                    "minAmountMinor must not exceed maxAmountMinor"
                }
                val defaultAmountFrom = arguments.optionalLong("minDefaultCurrencyAmountMinor")
                val defaultAmountTo = arguments.optionalLong("maxDefaultCurrencyAmountMinor")
                require(defaultAmountFrom == null || defaultAmountTo == null || defaultAmountFrom <= defaultAmountTo) {
                    "minDefaultCurrencyAmountMinor must not exceed maxDefaultCurrencyAmountMinor"
                }
                val recurringInstanceFrom = arguments.optionalDate("recurringInstanceFrom")
                val recurringInstanceTo = arguments.optionalDate("recurringInstanceTo")
                require(
                    recurringInstanceFrom == null || recurringInstanceTo == null ||
                        !recurringInstanceFrom.isAfter(recurringInstanceTo),
                ) { "recurringInstanceFrom must not be after recurringInstanceTo" }
                val result = transactionService.queryTransactions(
                    userId = userId,
                    type = type,
                    criteria = TransactionQueryCriteria(
                        from = from,
                        to = to,
                        transactionIds = arguments.longList("transactionIds"),
                        categoryIds = arguments.longList("categoryIds"),
                        categoryNameQuery = arguments.optionalText("categoryNameQuery"),
                        accountIds = arguments.longList("accountIds"),
                        accountNameQuery = arguments.optionalText("accountNameQuery"),
                        notesTokens = arguments.requiredText("notesQuery").words(),
                        currencies = arguments.textList("currencies"),
                        amountMinorFrom = amountMinorFrom,
                        amountMinorTo = amountMinorTo,
                        defaultCurrencies = arguments.textList("defaultCurrencies"),
                        defaultCurrencyAmountMinorFrom = defaultAmountFrom,
                        defaultCurrencyAmountMinorTo = defaultAmountTo,
                        conversionSources = arguments.enumList<DefaultCurrencyConversionSource>("conversionSources"),
                        conversionTransferIds = arguments.longList("conversionTransferIds"),
                        recurring = arguments.optionalBoolean("recurring"),
                        recurringRuleIds = arguments.longList("recurringRuleIds"),
                        recurringInstanceFrom = recurringInstanceFrom,
                        recurringInstanceTo = recurringInstanceTo,
                        recurringLocked = arguments.optionalBoolean("recurringLocked"),
                        metadataSources = arguments.textList("metadataSources"),
                    ),
                    orderBy = enumValue<TransactionQueryOrder>(arguments.requiredText("orderBy")),
                    direction = enumValue<SortDirection>(arguments.requiredText("direction")),
                    offset = arguments.requiredText("offset").toInt(),
                    limit = arguments.requiredText("limit").toInt(),
                )
                ExecutedAiChatTool(
                    call.id,
                    "Searching transactions",
                    "Searched transactions",
                    objectMapper.writeValueAsString(result),
                )
            }
            NET_WORTH -> {
                val range = arguments.dateRange()
                require(!range.second.isAfter(currentDate)) { "to must not be after today" }
                val granularity = enumValue<TransactionTimeSeriesGranularity>(arguments.requiredText("granularity"))
                val timeSeries = netWorthAnalyticsService.getTimeSeries(
                    userId,
                    range.first,
                    range.second,
                    granularity,
                    currentDate,
                )
                ExecutedAiChatTool(
                    call.id,
                    "Calculating net worth",
                    "Calculated net worth",
                    objectMapper.writeValueAsString(timeSeries),
                    chartSource = timeSeries.points.firstOrNull()?.let { first ->
                        AiChatChartSource.Line(
                            currency = first.currency,
                            seriesName = "Net worth",
                            points = timeSeries.points.map { AiChatChartSourcePoint(it.bucket, it.amountMinor) },
                        )
                    },
                )
            }
            TRANSFER_SEARCH -> {
                val range = arguments.dateRange()
                val matches = fundsTransferService.listTransfers(userId, FundsTransferDateFilter(range.first, range.second))
                val rows = matches.take(RESULT_LIMIT).map { details ->
                    AiTransferResult(
                        id = details.transfer.id!!,
                        date = details.transfer.date,
                        sourceAccountName = details.sourceAccount.name,
                        sourceCurrency = details.sourceAccount.currency,
                        sourceAmountMinor = details.transfer.sourceAmountMinor,
                        targetAccountName = details.targetAccount.name,
                        targetCurrency = details.targetAccount.currency,
                        targetAmountMinor = details.transfer.targetAmountMinor,
                    )
                }
                ExecutedAiChatTool(
                    call.id,
                    "Searching transfers",
                    "Searched transfers",
                    objectMapper.writeValueAsString(AiSearchResult(rows, matches.size > RESULT_LIMIT)),
                )
            }
            AiChatCharts.PRESENT_CHART_TOOL -> {
                val kind = enumValue<AiChatChartKind>(arguments.requiredText("kind"))
                val source = context.chartSources.values.lastOrNull {
                    when (kind) {
                        AiChatChartKind.LINE -> it is AiChatChartSource.Line
                        AiChatChartKind.PIE, AiChatChartKind.DONUT -> it is AiChatChartSource.Slices
                    }
                } ?: throw IllegalArgumentException("chart requires compatible data from this turn")
                val chart = charts.create(
                    kind = kind,
                    title = arguments.requiredText("title"),
                    source = source,
                )
                ExecutedAiChatTool(
                    call.id,
                    "Preparing chart",
                    "Prepared chart",
                    charts.encodeArtifact(chart),
                    chart = chart,
                )
            }
            else -> error("Unsupported AI tool request")
        }
    }

    private fun JsonNode.transactionType(): TransactionType = enumValue(requiredText("type"))

    private fun JsonNode.dateRange(): Pair<LocalDate, LocalDate> {
        val from = LocalDate.parse(requiredText("from"))
        val to = LocalDate.parse(requiredText("to"))
        require(!from.isAfter(to)) { "from must not be after to" }
        return Pair(from, to)
    }

    private fun JsonNode.requiredText(name: String): String {
        require(has(name) && path(name).isTextual) { "$name must be a string" }
        return path(name).asText()
    }

    private fun JsonNode.optionalText(name: String): String? = requiredText(name).takeIf(String::isNotBlank)

    private fun JsonNode.optionalLong(name: String): Long? = optionalText(name)?.toLong()

    private fun JsonNode.optionalDate(name: String): LocalDate? = optionalText(name)?.let(LocalDate::parse)

    private fun JsonNode.textList(name: String): List<String> = requiredText(name).split(',')
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun JsonNode.longList(name: String): List<Long> = textList(name).map { value ->
        value.toLong().also { require(it > 0) { "$name values must be positive" } }
    }

    private inline fun <reified T : Enum<T>> JsonNode.enumList(name: String): List<T> =
        textList(name).map(::enumValue)

    private fun JsonNode.optionalBoolean(name: String): Boolean? = when (val value = requiredText(name).uppercase()) {
        "ANY" -> null
        "TRUE" -> true
        "FALSE" -> false
        else -> throw IllegalArgumentException("$name must be ANY, TRUE, or FALSE, but was $value")
    }

    private fun String.words(): List<String> = split(Regex("\\s+")).filter(String::isNotBlank)

    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValueOf(value.uppercase())

    private fun tool(name: String, description: String, parameters: JsonObjectSchema): ToolSpecification =
        ToolSpecification.builder().name(name).description(description).parameters(parameters).strict(true).build()

    private fun parameters(vararg properties: Pair<String, String>): JsonObjectSchema =
        JsonObjectSchema.builder().apply {
            properties.forEach { (name, description) -> addStringProperty(name, description) }
            required(*properties.map(Pair<String, String>::first).toTypedArray())
            additionalProperties(false)
        }.build()

    companion object {
        private const val ACCOUNT_BALANCES = "get_account_balances"
        private const val CATEGORY_TOTALS = "get_category_totals"
        private const val TRANSACTION_SEARCH = "search_transactions"
        private const val TRANSACTION_TIME_SERIES = "get_transaction_time_series"
        private const val NET_WORTH = "get_net_worth"
        private const val TRANSFER_SEARCH = "search_transfers"
        private const val RESULT_LIMIT = 50
    }
}

data class ExecutedAiChatTool(
    val activityId: String,
    val startedLabel: String,
    val completedLabel: String,
    val result: String,
    val chartSource: AiChatChartSource? = null,
    val chart: AiChatChartResponse? = null,
)

private data class AiSearchResult<T>(val items: List<T>, val truncated: Boolean)

private data class AiTransferResult(
    val id: Long,
    val date: LocalDate,
    val sourceAccountName: String,
    val sourceCurrency: String,
    val sourceAmountMinor: Long,
    val targetAccountName: String,
    val targetCurrency: String,
    val targetAmountMinor: Long,
)
