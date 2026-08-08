package io.orangebuffalo.renalo

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.orangebuffalo.renalo.ai.AiChatChartAxisResponse
import io.orangebuffalo.renalo.ai.AiChatChartAxisType
import io.orangebuffalo.renalo.ai.AiChatChartKind
import io.orangebuffalo.renalo.ai.AiChatChartOrientation
import io.orangebuffalo.renalo.ai.AiChatChartPointResponse
import io.orangebuffalo.renalo.ai.AiChatChartResponse
import io.orangebuffalo.renalo.ai.AiChatChartSeriesResponse
import io.orangebuffalo.renalo.ai.AiChatChartValueAxisResponse
import io.orangebuffalo.renalo.ai.AiChatChartValueType
import io.orangebuffalo.renalo.ai.AiChatCharts
import org.junit.jupiter.api.Test

class AiChatChartsTest {
    private val charts = AiChatCharts()
    private val objectMapper = ObjectMapper()

    @Test
    fun createsAndRoundTripsFlexibleMultiSeriesCharts() {
        val chart = charts.create(
            objectMapper.readTree(
                """
                    {
                      "kind":"BAR",
                      "title":"Spending by account and category",
                      "xAxisLabel":"Account",
                      "xAxisType":"CATEGORY",
                      "yAxisLabel":"Expenses",
                      "yAxisType":"MONEY_MINOR",
                      "currency":"AUD",
                      "stacked":true,
                      "orientation":"HORIZONTAL",
                      "series":[
                        {"name":"Groceries","points":[{"x":"Daily","y":"${Long.MAX_VALUE}"},{"x":"Savings","y":"1200"}]},
                        {"name":"Dining","points":[{"x":"Daily","y":"3400"},{"x":"Savings","y":"500"}]}
                      ]
                    }
                """.trimIndent(),
            ),
            "00000000-0000-0000-0000-000000000001",
        )

        chart.shouldBe(
            AiChatChartResponse(
                id = "00000000-0000-0000-0000-000000000001",
                kind = AiChatChartKind.BAR,
                title = "Spending by account and category",
                xAxis = AiChatChartAxisResponse("Account", AiChatChartAxisType.CATEGORY),
                yAxis = AiChatChartValueAxisResponse("Expenses", AiChatChartValueType.MONEY_MINOR, "AUD"),
                stacked = true,
                orientation = AiChatChartOrientation.HORIZONTAL,
                series = listOf(
                    AiChatChartSeriesResponse(
                        "Groceries",
                        listOf(
                            AiChatChartPointResponse("Daily", Long.MAX_VALUE.toString()),
                            AiChatChartPointResponse("Savings", "1200"),
                        ),
                    ),
                    AiChatChartSeriesResponse(
                        "Dining",
                        listOf(AiChatChartPointResponse("Daily", "3400"), AiChatChartPointResponse("Savings", "500")),
                    ),
                ),
            ),
        )
        charts.decodeArtifact(charts.encodeArtifact(chart)).shouldBe(chart)
    }

    @Test
    fun supportsAllChartKindsAndGeneralNumbers() {
        AiChatChartKind.entries.forEach { kind ->
            val xAxisType = if (kind == AiChatChartKind.SCATTER) "NUMBER" else "DATE"
            val chart = charts.create(
                objectMapper.readTree(
                    """
                        {
                          "kind":"$kind",
                          "title":"Flexible chart",
                          "xAxisLabel":"Any grouping",
                          "xAxisType":"$xAxisType",
                          "yAxisLabel":"Rate",
                          "yAxisType":"NUMBER",
                          "currency":"",
                          "stacked":false,
                          "orientation":"VERTICAL",
                          "series":[{"name":"Observed","points":[{"x":"${if (xAxisType == "NUMBER") "1.5" else "2026-01-01"}","y":"12.345"}]}]
                        }
                    """.trimIndent(),
                ),
            )
            chart.kind.shouldBe(kind)
            chart.yAxis.currency.shouldBe(null)
        }
    }

    @Test
    fun rejectsUnsafeOrIncompatibleCharts() {
        shouldThrow<IllegalArgumentException> {
            charts.create(arguments(kind = "PIE", y = "-1"))
        }
        shouldThrow<IllegalArgumentException> {
            charts.create(arguments(kind = "SCATTER", xAxisType = "CATEGORY"))
        }
        shouldThrow<IllegalArgumentException> {
            charts.create(arguments(kind = "LINE", stacked = true))
        }
        shouldThrow<IllegalArgumentException> {
            charts.create(arguments(kind = "BAR", currency = "INVALID"))
        }
        charts.decodeArtifact("""{"type":"renalo_chart","version":1,"chart":{"kind":"PIE"}}""").shouldBe(null)
    }

    private fun arguments(
        kind: String,
        xAxisType: String = "CATEGORY",
        y: String = "1",
        stacked: Boolean = false,
        currency: String = "AUD",
    ) = objectMapper.readTree(
        """
            {
              "kind":"$kind",
              "title":"Chart",
              "xAxisLabel":"Group",
              "xAxisType":"$xAxisType",
              "yAxisLabel":"Amount",
              "yAxisType":"MONEY_MINOR",
              "currency":"$currency",
              "stacked":$stacked,
              "orientation":"VERTICAL",
              "series":[{"name":"Values","points":[{"x":"Group A","y":"$y"}]}]
            }
        """.trimIndent(),
    )
}
