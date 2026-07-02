package io.orangebuffalo.renalo.tracking

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.user.UserRepository

@Controller("/api/tracking/dashboard")
@Secured(UserRoles.USER)
class DashboardController(
    private val userRepository: UserRepository,
    private val dashboardService: DashboardService,
) {
    @Get("/accounts")
    fun accountSummaries(authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return HttpResponse.ok(dashboardService.getAccountSummaries(user.id!!))
    }
}
