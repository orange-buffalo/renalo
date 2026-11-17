package io.orangebuffalo.renalo.tracking

import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.time.CLIENT_TIME_ZONE_HEADER
import io.orangebuffalo.renalo.time.TimeProvider
import io.orangebuffalo.renalo.time.parseClientTimeZone
import io.orangebuffalo.renalo.user.UserRepository
import java.util.Currency

@Controller("/api/tracking/expense-refunds")
@Secured(UserRoles.USER)
class ExpenseRefundController(
    private val users: UserRepository,
    private val refunds: ExpenseRefundService,
    private val time: TimeProvider,
) {
    @Post("/search")
    fun search(
        authentication: Authentication,
        @Body request: RefundSearch,
        @Header(CLIENT_TIME_ZONE_HEADER) timeZone: String?,
    ): HttpResponse<*> {
        val user = users.findByUsername(authentication.name) ?: return HttpResponse.unauthorized<Any>()
        val zone = parseClientTimeZone(timeZone) ?: return HttpResponse.badRequest<Any>()
        if (!valid(request)) return HttpResponse.badRequest<Any>()
        return HttpResponse.ok(refunds.search(user.id!!, request, time.today(zone)))
    }

    @Post
    fun save(
        authentication: Authentication,
        @Body request: SaveExpenseRefund,
        @Header(CLIENT_TIME_ZONE_HEADER) timeZone: String?,
    ): HttpResponse<*> {
        val user = users.findByUsername(authentication.name) ?: return HttpResponse.unauthorized<Any>()
        val zone = parseClientTimeZone(timeZone) ?: return HttpResponse.badRequest<Any>()
        if (!valid(request.search)) return HttpResponse.badRequest<Any>()
        return if (refunds.save(user.id!!, request, time.today(zone))) {
            HttpResponse.noContent<Any>()
        } else {
            HttpResponse.status<Any>(HttpStatus.CONFLICT)
        }
    }

    private fun valid(search: RefundSearch) =
        search.amountMinor > 0 && Currency.getAvailableCurrencies().any { it.currencyCode == search.currency }
}
