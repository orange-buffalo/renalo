package io.orangebuffalo.renalo.tracking

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.time.CLIENT_TIME_ZONE_HEADER
import io.orangebuffalo.renalo.time.TimeProvider
import io.orangebuffalo.renalo.time.parseClientTimeZone
import io.orangebuffalo.renalo.user.UserRepository
import java.time.LocalDate

@Controller("/api/tracking/analytics/net-worth")
@Secured(UserRoles.USER)
class NetWorthAnalyticsController(
    private val userRepository: UserRepository,
    private val netWorthAnalyticsService: NetWorthAnalyticsService,
    private val timeProvider: TimeProvider,
) {
    @Get("/time-series")
    fun getTimeSeries(
        authentication: Authentication,
        @Header(CLIENT_TIME_ZONE_HEADER) timeZone: String?,
        @QueryValue from: LocalDate?,
        @QueryValue to: LocalDate?,
        @QueryValue(defaultValue = "AUTO") granularity: TransactionTimeSeriesGranularity,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val clientTimeZone = parseClientTimeZone(timeZone) ?: return HttpResponse.badRequest<Any>()
        val currentDate = timeProvider.today(clientTimeZone)
        if (from != null && from.isAfter(minOf(to ?: currentDate, currentDate))) {
            return HttpResponse.badRequest<Any>()
        }
        val timeSeries = netWorthAnalyticsService.getTimeSeries(user.id!!, from, to, granularity, currentDate)
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
