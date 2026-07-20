package io.orangebuffalo.renalo.tracking

import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.user.UserRepository
import java.time.LocalDate

@Controller("/api/tracking/analytics/transactions")
@Secured(UserRoles.USER)
class TransactionAnalyticsController(
    private val userRepository: UserRepository,
    private val transactionService: TransactionService,
) {
    @Get("/{type}/time-series")
    fun getTimeSeries(
        type: TransactionType,
        authentication: Authentication,
        @QueryValue from: LocalDate?,
        @QueryValue to: LocalDate?,
        @QueryValue categoryIds: String?,
        @QueryValue accountIds: String?,
        @QueryValue notes: String?,
        @QueryValue(defaultValue = "AUTO") granularity: TransactionTimeSeriesGranularity,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val filter = parseTransactionFilter(from, to, categoryIds, accountIds, notes)
            ?: return HttpResponse.badRequest<Any>()
        val timeSeries = transactionService.getTimeSeries(user.id!!, type, filter, granularity)
        return HttpResponse.ok(
            TransactionTimeSeriesResponse(
                granularity = timeSeries.granularity,
                from = timeSeries.from,
                to = timeSeries.to,
                points = timeSeries.points.map {
                    TransactionTimeSeriesPointResponse(it.bucket, it.currency, it.amountMinor)
                },
            ),
        )
    }
}

data class TransactionTimeSeriesResponse(
    val granularity: TransactionTimeSeriesGranularity,
    val from: LocalDate?,
    val to: LocalDate?,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val points: List<TransactionTimeSeriesPointResponse>,
)

data class TransactionTimeSeriesPointResponse(
    val bucket: LocalDate,
    val currency: String,
    val amountMinor: Long,
)
