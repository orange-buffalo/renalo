package io.orangebuffalo.renalo.ai

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

enum class AiChatChartKind {
    LINE,
    AREA,
    BAR,
    PIE,
    DONUT,
    SCATTER,
}

enum class AiChatChartAxisType {
    CATEGORY,
    DATE,
    NUMBER,
}

enum class AiChatChartValueType {
    MONEY_MINOR,
    NUMBER,
}

enum class AiChatChartOrientation {
    VERTICAL,
    HORIZONTAL,
}

data class AiChatChartResponse(
    val id: String,
    val kind: AiChatChartKind,
    val title: String,
    @get:JsonProperty("xAxis")
    val xAxis: AiChatChartAxisResponse,
    @get:JsonProperty("yAxis")
    val yAxis: AiChatChartValueAxisResponse,
    val stacked: Boolean,
    val orientation: AiChatChartOrientation,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val series: List<AiChatChartSeriesResponse>,
)

data class AiChatChartAxisResponse(
    val label: String,
    val type: AiChatChartAxisType,
)

data class AiChatChartValueAxisResponse(
    val label: String,
    val type: AiChatChartValueType,
    val currency: String? = null,
)

data class AiChatChartSeriesResponse(
    val name: String,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val points: List<AiChatChartPointResponse>,
)

data class AiChatChartPointResponse(
    val x: String,
    val y: String,
)

@Singleton
class AiChatCharts {
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    fun create(arguments: JsonNode, id: String = UUID.randomUUID().toString()): AiChatChartResponse = validate(
        AiChatChartResponse(
            id = id,
            kind = arguments.requiredEnum("kind"),
            title = arguments.requiredText("title"),
            xAxis = AiChatChartAxisResponse(
                label = arguments.requiredText("xAxisLabel"),
                type = arguments.requiredEnum("xAxisType"),
            ),
            yAxis = AiChatChartValueAxisResponse(
                label = arguments.requiredText("yAxisLabel"),
                type = arguments.requiredEnum("yAxisType"),
                currency = arguments.path("currency").asText().trim().ifEmpty { null },
            ),
            stacked = arguments.path("stacked").asBoolean(),
            orientation = arguments.requiredEnum("orientation"),
            series = arguments.path("series").map { series ->
                AiChatChartSeriesResponse(
                    name = series.requiredText("name"),
                    points = series.path("points").map { point ->
                        AiChatChartPointResponse(
                            x = point.requiredText("x"),
                            y = point.requiredText("y"),
                        )
                    },
                )
            },
        ),
    )

    fun encodeArtifact(chart: AiChatChartResponse): String = objectMapper.writeValueAsString(
        mapOf("type" to ARTIFACT_TYPE, "version" to 1, "chart" to chart),
    )

    fun decodeArtifact(value: String): AiChatChartResponse? {
        val root = runCatching { objectMapper.readTree(value) }.getOrNull() ?: return null
        if (root.path("type").asText() != ARTIFACT_TYPE || root.path("version").asInt() != 1) return null
        return runCatching {
            val chart = root.path("chart")
            validate(
                AiChatChartResponse(
                    id = chart.requiredText("id"),
                    kind = chart.requiredEnum("kind"),
                    title = chart.requiredText("title"),
                    xAxis = AiChatChartAxisResponse(
                        label = chart.path("xAxis").requiredText("label"),
                        type = chart.path("xAxis").requiredEnum("type"),
                    ),
                    yAxis = AiChatChartValueAxisResponse(
                        label = chart.path("yAxis").requiredText("label"),
                        type = chart.path("yAxis").requiredEnum("type"),
                        currency = chart.path("yAxis").path("currency")
                            .takeIf(JsonNode::isTextual)
                            ?.asText()
                            ?.trim()
                            ?.ifEmpty { null },
                    ),
                    stacked = chart.path("stacked").asBoolean(),
                    orientation = chart.requiredEnum("orientation"),
                    series = chart.path("series").map { series ->
                        AiChatChartSeriesResponse(
                            name = series.requiredText("name"),
                            points = series.path("points").map { point ->
                                AiChatChartPointResponse(point.requiredText("x"), point.requiredText("y"))
                            },
                        )
                    },
                ),
            )
        }.getOrNull()
    }

    private fun validate(chart: AiChatChartResponse): AiChatChartResponse {
        UUID.fromString(chart.id)
        validateLabel(chart.title, "chart title", MAX_TITLE_LENGTH)
        validateLabel(chart.xAxis.label, "x-axis label")
        validateLabel(chart.yAxis.label, "y-axis label")
        when (chart.yAxis.type) {
            AiChatChartValueType.MONEY_MINOR -> Currency.getInstance(requireNotNull(chart.yAxis.currency) {
                "money charts require a currency"
            })
            AiChatChartValueType.NUMBER -> require(chart.yAxis.currency == null) {
                "number charts must not specify a currency"
            }
        }
        require(chart.series.isNotEmpty() && chart.series.size <= MAX_SERIES) {
            "charts require 1 to $MAX_SERIES series"
        }
        require(chart.series.map { it.name }.distinct().size == chart.series.size) { "chart series names must be unique" }
        require(chart.series.sumOf { it.points.size } <= MAX_POINTS) { "charts support at most $MAX_POINTS points" }
        chart.series.forEach { series ->
            validateLabel(series.name, "series name")
            require(series.points.isNotEmpty()) { "chart series must contain at least one point" }
            require(series.points.map { it.x }.distinct().size == series.points.size) {
                "x-axis values must be unique within each series"
            }
            series.points.forEach { point ->
                validateLabel(point.x, "x-axis value")
                when (chart.xAxis.type) {
                    AiChatChartAxisType.CATEGORY -> Unit
                    AiChatChartAxisType.DATE -> LocalDate.parse(point.x)
                    AiChatChartAxisType.NUMBER -> point.x.requiredDecimal("x-axis value")
                }
                when (chart.yAxis.type) {
                    AiChatChartValueType.MONEY_MINOR -> requireNotNull(point.y.toLongOrNull()) {
                        "money values must be signed 64-bit integer minor units"
                    }
                    AiChatChartValueType.NUMBER -> point.y.requiredDecimal("chart value")
                }
            }
        }
        when (chart.kind) {
            AiChatChartKind.PIE, AiChatChartKind.DONUT -> {
                require(chart.series.size == 1) { "${chart.kind} charts require exactly one series" }
                require(chart.series.single().points.all { BigDecimal(it.y).signum() >= 0 }) {
                    "${chart.kind} chart values must not be negative"
                }
                require(!chart.stacked) { "${chart.kind} charts cannot be stacked" }
            }
            AiChatChartKind.SCATTER -> {
                require(chart.xAxis.type == AiChatChartAxisType.NUMBER) { "SCATTER charts require a numeric x-axis" }
                require(!chart.stacked) { "SCATTER charts cannot be stacked" }
            }
            AiChatChartKind.LINE -> require(!chart.stacked) { "LINE charts cannot be stacked" }
            AiChatChartKind.AREA, AiChatChartKind.BAR -> Unit
        }
        require(chart.orientation == AiChatChartOrientation.VERTICAL || chart.kind == AiChatChartKind.BAR) {
            "horizontal orientation is only supported for BAR charts"
        }
        return chart
    }

    private fun validateLabel(value: String, description: String, maxLength: Int = MAX_LABEL_LENGTH) {
        require(value.isNotBlank() && value.length <= maxLength) {
            "$description must contain 1 to $maxLength characters"
        }
        require(value.none(Char::isISOControl)) { "$description must not contain control characters" }
    }

    private fun String.requiredDecimal(description: String) {
        val value = runCatching { BigDecimal(this) }.getOrNull()
        require(value != null && value.precision() <= MAX_NUMBER_PRECISION && value.scale() <= MAX_NUMBER_SCALE) {
            "$description must be a decimal with at most $MAX_NUMBER_PRECISION digits and $MAX_NUMBER_SCALE decimal places"
        }
    }

    private fun JsonNode.requiredText(name: String): String = path(name).asText().trim().also {
        require(it.isNotEmpty()) { "$name must not be empty" }
    }

    private inline fun <reified T : Enum<T>> JsonNode.requiredEnum(name: String): T =
        enumValueOf(requiredText(name).uppercase())

    companion object {
        const val PRESENT_CHART_TOOL = "present_chart"
        private const val ARTIFACT_TYPE = "renalo_chart"
        private const val MAX_TITLE_LENGTH = 100
        private const val MAX_LABEL_LENGTH = 80
        private const val MAX_SERIES = 12
        private const val MAX_POINTS = 500
        private const val MAX_NUMBER_PRECISION = 100
        private const val MAX_NUMBER_SCALE = 20
    }
}
