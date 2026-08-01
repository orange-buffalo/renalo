package io.orangebuffalo.renalo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.orangebuffalo.renalo.ai.AiChatChartKind
import io.orangebuffalo.renalo.ai.AiChatChartSource
import io.orangebuffalo.renalo.ai.AiChatChartSourcePoint
import io.orangebuffalo.renalo.ai.AiChatChartSourceSegment
import io.orangebuffalo.renalo.ai.AiChatCharts
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AiChatChartsTest {
    private val charts = AiChatCharts()

    @Test
    fun createsAndRoundTripsExactLineCharts() {
        val chart = charts.create(
            kind = AiChatChartKind.LINE,
            title = "Net worth",
            source = AiChatChartSource.Line(
                currency = "AUD",
                seriesName = "Balance",
                points = listOf(
                    AiChatChartSourcePoint(LocalDate.parse("2026-02-01"), Long.MAX_VALUE),
                    AiChatChartSourcePoint(LocalDate.parse("2026-01-01"), Long.MIN_VALUE),
                ),
            ),
            id = "00000000-0000-0000-0000-000000000001",
        )

        chart.shouldBe(
            io.orangebuffalo.renalo.ai.AiChatChartResponse(
                id = "00000000-0000-0000-0000-000000000001",
                kind = AiChatChartKind.LINE,
                title = "Net worth",
                currency = "AUD",
                series = listOf(
                    io.orangebuffalo.renalo.ai.AiChatChartSeriesResponse(
                        name = "Balance",
                        points = listOf(
                            io.orangebuffalo.renalo.ai.AiChatChartPointResponse("2026-01-01", Long.MIN_VALUE.toString()),
                            io.orangebuffalo.renalo.ai.AiChatChartPointResponse("2026-02-01", Long.MAX_VALUE.toString()),
                        ),
                    ),
                ),
            ),
        )
        charts.decodeArtifact(charts.encodeArtifact(chart)).shouldBe(chart)
    }

    @Test
    fun createsPieAndDonutChartsFromPositiveSlices() {
        val source = AiChatChartSource.Slices(
            currency = "AUD",
            segments = listOf(
                AiChatChartSourceSegment("Groceries", 12_345),
                AiChatChartSourceSegment("Unused", 0),
                AiChatChartSourceSegment("Rent", 90_000),
            ),
        )

        listOf(AiChatChartKind.PIE, AiChatChartKind.DONUT).forEach { kind ->
            val chart = charts.create(
                kind,
                "Spending",
                source,
                "00000000-0000-0000-0000-00000000000${if (kind == AiChatChartKind.PIE) 2 else 3}",
            )
            chart.kind.shouldBe(kind)
            chart.segments.shouldBe(
                listOf(
                    io.orangebuffalo.renalo.ai.AiChatChartSegmentResponse("Groceries", "12345"),
                    io.orangebuffalo.renalo.ai.AiChatChartSegmentResponse("Rent", "90000"),
                ),
            )
            chart.series.shouldBe(emptyList())
            charts.decodeArtifact(charts.encodeArtifact(chart)).shouldBe(chart)
        }
    }

    @Test
    fun rejectsIncompatibleOrUnsafeCharts() {
        shouldThrow<IllegalArgumentException> {
            charts.create(
                AiChatChartKind.LINE,
                "Wrong source",
                AiChatChartSource.Slices("AUD", listOf(AiChatChartSourceSegment("Food", 1))),
            )
        }
        shouldThrow<IllegalArgumentException> {
            charts.create(
                AiChatChartKind.PIE,
                "Negative",
                AiChatChartSource.Slices("AUD", listOf(AiChatChartSourceSegment("Debt", -1))),
            )
        }
        charts.decodeArtifact("""{"type":"renalo_chart","version":1,"chart":{"kind":"PIE"}}""").shouldBe(null)
    }
}
