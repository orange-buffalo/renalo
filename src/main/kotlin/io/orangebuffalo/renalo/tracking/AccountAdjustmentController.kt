package io.orangebuffalo.renalo.tracking

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.time.CLIENT_TIME_ZONE_HEADER
import io.orangebuffalo.renalo.time.TimeProvider
import io.orangebuffalo.renalo.time.parseClientTimeZone
import io.orangebuffalo.renalo.user.UserRepository

@Controller("/api/tracking/accounts")
@Secured(UserRoles.USER)
class AccountAdjustmentController(
    private val userRepository: UserRepository,
    private val accountAdjustmentService: AccountAdjustmentService,
    private val timeProvider: TimeProvider,
) {
    @Get("/{accountId}/adjustments")
    fun listAdjustments(
        accountId: Long,
        authentication: Authentication,
        @Header(CLIENT_TIME_ZONE_HEADER) timeZone: String?,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val clientTimeZone = parseClientTimeZone(timeZone) ?: return HttpResponse.badRequest<Any>()

        val data = accountAdjustmentService.getAdjustmentsWithBalance(user.id!!, accountId, timeProvider.today(clientTimeZone))
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(data)
    }

    @Post("/{accountId}/adjustments")
    fun createAdjustment(
        accountId: Long,
        authentication: Authentication,
        @Body request: CreateAdjustmentRequest,
        @Header(CLIENT_TIME_ZONE_HEADER) timeZone: String?,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val clientTimeZone = parseClientTimeZone(timeZone) ?: return HttpResponse.badRequest<Any>()

        return when (
            val result = accountAdjustmentService.createAdjustment(
                user.id!!,
                accountId,
                request.adjustmentAmountMinor,
                timeProvider.today(clientTimeZone),
            )
        ) {
            is CreateAdjustmentResult.Success -> HttpResponse.created(result.adjustment)
            CreateAdjustmentResult.InvalidAmount -> HttpResponse.badRequest<Any>()
            CreateAdjustmentResult.AccountNotFound -> HttpResponse.notFound<Any>()
        }
    }

    @Delete("/{accountId}/adjustments/{adjustmentId}")
    fun deleteAdjustment(
        accountId: Long,
        adjustmentId: Long,
        authentication: Authentication,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return when (accountAdjustmentService.deleteAdjustment(user.id!!, accountId, adjustmentId)) {
            DeleteAdjustmentResult.Deleted -> HttpResponse.noContent<Any>()
            DeleteAdjustmentResult.NotFound -> HttpResponse.notFound<Any>()
        }
    }
}
