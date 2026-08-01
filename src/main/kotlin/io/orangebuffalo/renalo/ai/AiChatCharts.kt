package io.orangebuffalo.renalo.ai

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Singleton
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

enum class AiChatChartKind {
    LINE,
    PIE,
    DONUT,
}

data class AiChatChartResponse(
    val id: String,
    val kind: AiChatChartKind,
    val title: String,
    val currency: String,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val series: List<AiChatChartSeriesResponse> = emptyList(),
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val segments: List<AiChatChartSegmentResponse> = emptyList(),
)

data class AiChatChartSeriesResponse(
    val name: String,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val points: List<AiChatChartPointResponse>,
)

data class AiChatChartPointResponse(
    val label: String,
    val amountMinor: String,
)

data class AiChatChartSegmentResponse(
    val label: String,
    val amountMinor: String,
)

sealed interface AiChatChartSource {
    data class Line(
        val currency: String,
        val seriesName: String,
        val points: List<AiChatChartSourcePoint>,
    ) : AiChatChartSource

    data class Slices(
        val currency: String,
        val segments: List<AiChatChartSourceSegment>,
    ) : AiChatChartSource
}

data class AiChatChartSourcePoint(val label: LocalDate, val amountMinor: Long)

data class AiChatChartSourceSegment(val label: String, val amountMinor: Long)

class AiChatToolExecutionContext {
    val chartSources = linkedMapOf<String, AiChatChartSource>()
}

@Singleton
class AiChatCharts {
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    fun create(
        kind: AiChatChartKind,
        title: String,
        source: AiChatChartSource,
        id: String = UUID.randomUUID().toString(),
    ): AiChatChartResponse {
        UUID.fromString(id)
        val validatedTitle = title.trim().also {
            require(it.isNotEmpty() && it.length <= MAX_TITLE_LENGTH) { "chart title must contain 1 to 100 characters" }
            require(it.none(Char::isISOControl)) { "chart title must not contain control characters" }
        }
        return when (kind) {
            AiChatChartKind.LINE -> {
                require(source is AiChatChartSource.Line) { "LINE charts require time-series data" }
                validateCurrency(source.currency)
                require(source.seriesName.isNotBlank() && source.seriesName.length <= MAX_LABEL_LENGTH) {
                    "line series name must contain 1 to 80 characters"
                }
                require(source.points.isNotEmpty() && source.points.size <= MAX_LINE_POINTS) {
                    "line charts require 1 to 200 points"
                }
                require(source.points.map { it.label }.distinct().size == source.points.size) {
                    "line chart point labels must be unique"
                }
                AiChatChartResponse(
                    id = id,
                    kind = kind,
                    title = validatedTitle,
                    currency = source.currency,
                    series = listOf(
                        AiChatChartSeriesResponse(
                            name = source.seriesName,
                            points = source.points.sortedBy { it.label }.map {
                                AiChatChartPointResponse(it.label.toString(), it.amountMinor.toString())
                            },
                        ),
                    ),
                )
            }
            AiChatChartKind.PIE, AiChatChartKind.DONUT -> {
                require(source is AiChatChartSource.Slices) { "$kind charts require category data" }
                validateCurrency(source.currency)
                val segments = source.segments.filter { it.amountMinor > 0 }
                require(segments.isNotEmpty() && segments.size <= MAX_SEGMENTS) {
                    "$kind charts require 1 to 100 positive segments"
                }
                require(source.segments.all { it.amountMinor >= 0 }) { "$kind chart values must not be negative" }
                require(segments.all { it.label.isNotBlank() && it.label.length <= MAX_LABEL_LENGTH }) {
                    "chart labels must contain 1 to 80 characters"
                }
                AiChatChartResponse(
                    id = id,
                    kind = kind,
                    title = validatedTitle,
                    currency = source.currency,
                    segments = segments.map { AiChatChartSegmentResponse(it.label, it.amountMinor.toString()) },
                )
            }
        }
    }

    fun encodeArtifact(chart: AiChatChartResponse): String = objectMapper.writeValueAsString(
        mapOf("type" to ARTIFACT_TYPE, "version" to 1, "chart" to chart),
    )

    fun decodeArtifact(value: String): AiChatChartResponse? {
        val root = runCatching { objectMapper.readTree(value) }.getOrNull() ?: return null
        if (root.path("type").asText() != ARTIFACT_TYPE || root.path("version").asInt() != 1) return null
        val chartNode = root.path("chart")
        val kind = runCatching { AiChatChartKind.valueOf(chartNode.path("kind").asText()) }.getOrNull() ?: return null
        val id = chartNode.path("id").asText()
        val title = chartNode.path("title").asText()
        val currency = chartNode.path("currency").asText()
        return runCatching {
            when (kind) {
                AiChatChartKind.LINE -> create(
                    kind,
                    title,
                    AiChatChartSource.Line(
                        currency = currency,
                        seriesName = chartNode.path("series").path(0).path("name").asText(),
                        points = chartNode.path("series").path(0).path("points").map {
                            AiChatChartSourcePoint(
                                label = LocalDate.parse(it.path("label").asText()),
                                amountMinor = it.path("amountMinor").asText().toLong(),
                            )
                        },
                    ),
                    id,
                )
                AiChatChartKind.PIE, AiChatChartKind.DONUT -> create(
                    kind,
                    title,
                    AiChatChartSource.Slices(
                        currency = currency,
                        segments = chartNode.path("segments").map {
                            AiChatChartSourceSegment(
                                label = it.path("label").asText(),
                                amountMinor = it.path("amountMinor").asText().toLong(),
                            )
                        },
                    ),
                    id,
                )
            }
        }.getOrNull()
    }

    private fun validateCurrency(currency: String) {
        Currency.getInstance(currency)
    }

    companion object {
        const val PRESENT_CHART_TOOL = "present_chart"
        private const val ARTIFACT_TYPE = "renalo_chart"
        private const val MAX_TITLE_LENGTH = 100
        private const val MAX_LABEL_LENGTH = 80
        private const val MAX_SEGMENTS = 100
        private const val MAX_LINE_POINTS = 200
    }
}
