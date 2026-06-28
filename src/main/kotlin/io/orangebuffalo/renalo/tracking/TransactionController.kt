package io.orangebuffalo.renalo.tracking

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.Post
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.recurrence.RecurrenceDescriptionFormatter
import io.orangebuffalo.renalo.recurrence.RecurrenceSchedule
import io.orangebuffalo.renalo.user.UserRepository
import java.time.LocalDate

@Controller("/api/tracking/transactions")
@Secured(UserRoles.USER)
class TransactionController(
    private val userRepository: UserRepository,
    private val transactionService: TransactionService,
) {
    @Get("/{type}")
    fun listTransactions(type: TransactionType, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return HttpResponse.ok(
            transactionService.listTransactions(user.id!!, type).map { it.toResponse() },
        )
    }

    @Get("/{type}/{transactionId}")
    fun getTransaction(type: TransactionType, transactionId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val transaction = transactionService.findTransaction(user.id!!, type, transactionId)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(transaction.toResponse())
    }

    @Post("/{type}")
    fun createTransaction(
        type: TransactionType,
        authentication: Authentication,
        @Body request: SaveTransactionRequest,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return when (val result = transactionService.createTransaction(user.id!!, type, request)) {
            is SaveTransactionResult.Saved -> HttpResponse.created(result.transaction.toResponse())
            SaveTransactionResult.BadRequest -> HttpResponse.badRequest<Any>()
        }
    }

    @Patch("/{type}/{transactionId}")
    fun updateTransaction(
        type: TransactionType,
        transactionId: Long,
        authentication: Authentication,
        @Body request: SaveTransactionRequest,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val existingTransaction = transactionService.findTransaction(user.id!!, type, transactionId)
            ?: return HttpResponse.notFound<Any>()
        return when (val result = transactionService.updateTransaction(
            user.id!!,
            type,
            existingTransaction.transaction.id!!,
            request,
        )) {
            is SaveTransactionResult.Saved -> HttpResponse.ok(result.transaction.toResponse())
            SaveTransactionResult.BadRequest -> HttpResponse.badRequest<Any>()
        }
    }

    @Delete("/{type}/{transactionId}")
    fun deleteTransaction(
        type: TransactionType,
        transactionId: Long,
        authentication: Authentication,
        @Body request: DeleteTransactionRequest?,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return when (transactionService.deleteTransaction(user.id!!, type, transactionId, request)) {
            DeleteTransactionResult.Deleted -> HttpResponse.noContent<Any>()
            DeleteTransactionResult.NotFound -> HttpResponse.notFound<Any>()
            DeleteTransactionResult.BadRequest -> HttpResponse.badRequest<Any>()
        }
    }
}

private fun TransactionDetails.toResponse() = TransactionResponse(
    id = transaction.id ?: error("Transaction must be persisted before it can be returned"),
    trackingAccount = TransactionTrackingAccountResponse(
        id = account.id ?: error("Tracking account must be persisted before it can be returned"),
        name = account.name,
        currency = account.currency,
    ),
    category = TransactionCategorySummaryResponse(
        id = category.id,
        name = category.name,
    ),
    date = transaction.date,
    amountMinor = transaction.amountMinor,
    notes = transaction.notes,
    recurrence = recurrenceResponse(),
)

private fun TransactionDetails.recurrenceResponse(): TransactionRecurrenceResponse? {
    val rule = recurringRule ?: return null
    val instanceDate = transaction.recurringInstanceDate ?: return null
    return TransactionRecurrenceResponse(
        ruleId = rule.id ?: error("Recurring transaction rule must be persisted before it can be returned"),
        startDate = rule.startDate,
        endDate = rule.endDate,
        instanceDate = instanceDate,
        description = RecurrenceDescriptionFormatter.describe(
            RecurrenceSchedule(rule.recurrenceFrequency, rule.recurrenceInterval),
            rule.endDate,
        ),
    )
}

data class TransactionResponse(
    val id: Long,
    val trackingAccount: TransactionTrackingAccountResponse,
    val category: TransactionCategorySummaryResponse,
    val date: LocalDate,
    val amountMinor: Long,
    val notes: String?,
    val recurrence: TransactionRecurrenceResponse?,
)

data class TransactionRecurrenceResponse(
    val ruleId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val instanceDate: LocalDate,
    val description: String,
)

data class TransactionTrackingAccountResponse(
    val id: Long,
    val name: String,
    val currency: String,
)

data class TransactionCategorySummaryResponse(
    val id: Long,
    val name: String,
)
