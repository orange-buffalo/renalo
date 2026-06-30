package io.orangebuffalo.renalo.tracking

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.user.UserRepository
import java.time.LocalDate

@Controller("/api/tracking/funds-transfers")
@Secured(UserRoles.USER)
class FundsTransferController(
    private val userRepository: UserRepository,
    private val fundsTransferService: FundsTransferService,
) {
    @Get
    fun listTransfers(
        authentication: Authentication,
        @QueryValue from: LocalDate?,
        @QueryValue to: LocalDate?,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        if ((from == null) != (to == null) || (from != null && to != null && from > to)) {
            return HttpResponse.badRequest<Any>()
        }

        return HttpResponse.ok(
            fundsTransferService.listTransfers(user.id!!, FundsTransferDateFilter(from, to)).map { it.toResponse() },
        )
    }

    @Get("/{transferId}")
    fun getTransfer(transferId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val transfer = fundsTransferService.findTransfer(user.id!!, transferId)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(transfer.toResponse())
    }

    @Post
    fun createTransfer(authentication: Authentication, @Body request: SaveFundsTransferRequest): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return when (val result = fundsTransferService.createTransfer(user.id!!, request)) {
            is SaveFundsTransferResult.Saved -> HttpResponse.created(result.transfer.toResponse())
            SaveFundsTransferResult.BadRequest -> HttpResponse.badRequest<Any>()
            SaveFundsTransferResult.NotFound -> HttpResponse.notFound<Any>()
        }
    }

    @Patch("/{transferId}")
    fun updateTransfer(
        transferId: Long,
        authentication: Authentication,
        @Body request: SaveFundsTransferRequest,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return when (val result = fundsTransferService.updateTransfer(user.id!!, transferId, request)) {
            is SaveFundsTransferResult.Saved -> HttpResponse.ok(result.transfer.toResponse())
            SaveFundsTransferResult.BadRequest -> HttpResponse.badRequest<Any>()
            SaveFundsTransferResult.NotFound -> HttpResponse.notFound<Any>()
        }
    }

    @Delete("/{transferId}")
    fun deleteTransfer(transferId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return when (fundsTransferService.deleteTransfer(user.id!!, transferId)) {
            DeleteFundsTransferResult.Deleted -> HttpResponse.noContent<Any>()
            DeleteFundsTransferResult.NotFound -> HttpResponse.notFound<Any>()
        }
    }
}

private fun FundsTransferDetails.toResponse() = FundsTransferResponse(
    id = transfer.id ?: error("Funds transfer must be persisted before it can be returned"),
    sourceAccount = sourceAccount.toTransferAccountResponse(),
    targetAccount = targetAccount.toTransferAccountResponse(),
    sourceAmountMinor = transfer.sourceAmountMinor,
    targetAmountMinor = transfer.targetAmountMinor,
    date = transfer.date,
)

private fun TrackingAccount.toTransferAccountResponse() = FundsTransferAccountResponse(
    id = id ?: error("Tracking account must be persisted before it can be returned"),
    name = name,
    currency = currency,
)

data class FundsTransferResponse(
    val id: Long,
    val sourceAccount: FundsTransferAccountResponse,
    val targetAccount: FundsTransferAccountResponse,
    val sourceAmountMinor: Long,
    val targetAmountMinor: Long,
    val date: LocalDate,
)

data class FundsTransferAccountResponse(
    val id: Long,
    val name: String,
    val currency: String,
)
