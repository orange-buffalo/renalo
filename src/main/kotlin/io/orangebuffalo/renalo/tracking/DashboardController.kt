package io.orangebuffalo.renalo.tracking

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.time.CLIENT_TIME_ZONE_HEADER
import io.orangebuffalo.renalo.time.TimeProvider
import io.orangebuffalo.renalo.time.parseClientTimeZone
import io.orangebuffalo.renalo.user.UserRepository

@Controller("/api/tracking/dashboard")
@Secured(UserRoles.USER)
class DashboardController(
    private val userRepository: UserRepository,
    private val dashboardService: DashboardService,
    private val timeProvider: TimeProvider,
) {
    @Get("/accounts")
    fun accountSummaries(
        authentication: Authentication,
        @Header(CLIENT_TIME_ZONE_HEADER) timeZone: String?,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val clientTimeZone = parseClientTimeZone(timeZone) ?: return HttpResponse.badRequest<Any>()

        return HttpResponse.ok(dashboardService.getAccountSummaries(user.id!!, timeProvider.today(clientTimeZone)))
    }
}
