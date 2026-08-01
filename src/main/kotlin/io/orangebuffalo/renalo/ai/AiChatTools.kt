package io.orangebuffalo.renalo.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import io.orangebuffalo.renalo.tracking.DashboardService
import io.orangebuffalo.renalo.tracking.FundsTransferDateFilter
import io.orangebuffalo.renalo.tracking.FundsTransferService
import io.orangebuffalo.renalo.tracking.NetWorthAnalyticsService
import io.orangebuffalo.renalo.tracking.TransactionDateFilter
import io.orangebuffalo.renalo.tracking.TransactionService
import io.orangebuffalo.renalo.tracking.TransactionTimeSeriesGranularity
import io.orangebuffalo.renalo.tracking.TransactionType
import jakarta.inject.Singleton
import java.time.LocalDate

@Singleton
class AiChatTools(
    private val dashboardService: DashboardService,
    private val transactionService: TransactionService,
    private val netWorthAnalyticsService: NetWorthAnalyticsService,
    private val fundsTransferService: FundsTransferService,
) {
    private val objectMapper = ObjectMapper().findAndRegisterModules()

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
            description = "Search expense or income transactions in an inclusive date range. Returns at most 50 newest matches; notesQuery may be empty.",
            parameters = parameters(
                "type" to "EXPENSE or INCOME",
                "from" to "Inclusive date in YYYY-MM-DD format",
                "to" to "Inclusive date in YYYY-MM-DD format",
                "notesQuery" to "Optional notes text to match, or an empty string",
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
    )

    fun activity(call: AiChatModelToolCall): Pair<String, String> = when (call.name) {
        ACCOUNT_BALANCES -> "Reviewing account balances" to "Reviewed account balances"
        CATEGORY_TOTALS -> "Calculating category totals" to "Calculated category totals"
        TRANSACTION_SEARCH -> "Searching transactions" to "Searched transactions"
        NET_WORTH -> "Calculating net worth" to "Calculated net worth"
        TRANSFER_SEARCH -> "Searching transfers" to "Searched transfers"
        else -> "Reviewing financial data" to "Reviewed financial data"
    }

    fun execute(userId: Long, currentDate: LocalDate, call: AiChatModelToolCall): ExecutedAiChatTool {
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
                ExecutedAiChatTool(
                    call.id,
                    "Calculating category totals",
                    "Calculated category totals",
                    objectMapper.writeValueAsString(
                        transactionService.getCategoryTotals(userId, type, TransactionDateFilter(range.first, range.second)),
                    ),
                )
            }
            TRANSACTION_SEARCH -> {
                val type = arguments.transactionType()
                val range = arguments.dateRange()
                val notesQuery = arguments.requiredText("notesQuery")
                val matches = transactionService.listTransactions(
                    userId,
                    type,
                    TransactionDateFilter(
                        from = range.first,
                        to = range.second,
                        notesTokens = notesQuery.split(Regex("\\s+")).filter(String::isNotBlank),
                    ),
                )
                val rows = matches.take(RESULT_LIMIT).map { details ->
                    AiTransactionResult(
                        id = details.transaction.id!!,
                        type = details.transaction.type,
                        date = details.transaction.date,
                        amountMinor = details.transaction.amountMinor,
                        currency = details.account.currency,
                        accountName = details.account.name,
                        categoryName = details.category.name,
                        notes = details.transaction.notes,
                    )
                }
                ExecutedAiChatTool(
                    call.id,
                    "Searching transactions",
                    "Searched transactions",
                    objectMapper.writeValueAsString(AiSearchResult(rows, matches.size > RESULT_LIMIT)),
                )
            }
            NET_WORTH -> {
                val range = arguments.dateRange()
                require(!range.second.isAfter(currentDate)) { "to must not be after today" }
                val granularity = enumValue<TransactionTimeSeriesGranularity>(arguments.requiredText("granularity"))
                ExecutedAiChatTool(
                    call.id,
                    "Calculating net worth",
                    "Calculated net worth",
                    objectMapper.writeValueAsString(
                        netWorthAnalyticsService.getTimeSeries(userId, range.first, range.second, granularity, currentDate),
                    ),
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
)

private data class AiSearchResult<T>(val items: List<T>, val truncated: Boolean)

private data class AiTransactionResult(
    val id: Long,
    val type: TransactionType,
    val date: LocalDate,
    val amountMinor: Long,
    val currency: String,
    val accountName: String,
    val categoryName: String,
    val notes: String?,
)

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
