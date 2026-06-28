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

@Controller("/api/tracking/expenses")
@Secured(UserRoles.USER)
class ExpenseController(
    private val userRepository: UserRepository,
    private val transactionService: TransactionService,
) {
    @Get
    fun listExpenses(authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return HttpResponse.ok(
            transactionService.listTransactions(user.id!!, TransactionType.EXPENSE).map { it.toResponse() },
        )
    }

    @Get("/{expenseId}")
    fun getExpense(expenseId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val expense = transactionService.findTransaction(user.id!!, TransactionType.EXPENSE, expenseId)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(expense.toResponse())
    }

    @Post
    fun createExpense(authentication: Authentication, @Body request: SaveTransactionRequest): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return when (val result = transactionService.createTransaction(user.id!!, TransactionType.EXPENSE, request)) {
            is SaveTransactionResult.Saved -> HttpResponse.created(result.transaction.toResponse())
            SaveTransactionResult.BadRequest -> HttpResponse.badRequest<Any>()
        }
    }

    @Patch("/{expenseId}")
    fun updateExpense(
        expenseId: Long,
        authentication: Authentication,
        @Body request: SaveTransactionRequest,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val existingExpense = transactionService.findTransaction(user.id!!, TransactionType.EXPENSE, expenseId)
            ?: return HttpResponse.notFound<Any>()
        return when (val result = transactionService.updateTransaction(
            user.id!!,
            TransactionType.EXPENSE,
            existingExpense.transaction.id!!,
            request,
        )) {
            is SaveTransactionResult.Saved -> HttpResponse.ok(result.transaction.toResponse())
            SaveTransactionResult.BadRequest -> HttpResponse.badRequest<Any>()
        }
    }

    @Delete("/{expenseId}")
    fun deleteExpense(
        expenseId: Long,
        authentication: Authentication,
        @Body request: DeleteTransactionRequest?,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return when (transactionService.deleteTransaction(user.id!!, TransactionType.EXPENSE, expenseId, request)) {
            DeleteTransactionResult.Deleted -> HttpResponse.noContent<Any>()
            DeleteTransactionResult.NotFound -> HttpResponse.notFound<Any>()
            DeleteTransactionResult.BadRequest -> HttpResponse.badRequest<Any>()
        }
    }
}

private fun TransactionDetails.toResponse() = ExpenseResponse(
    id = transaction.id ?: error("Expense must be persisted before it can be returned"),
    trackingAccount = ExpenseTrackingAccountResponse(
        id = account.id ?: error("Tracking account must be persisted before it can be returned"),
        name = account.name,
        currency = account.currency,
    ),
    category = ExpenseCategorySummaryResponse(
        id = category.id ?: error("Expense category must be persisted before it can be returned"),
        name = category.name,
    ),
    date = transaction.date,
    amountMinor = transaction.amountMinor,
    notes = transaction.notes,
    recurrence = recurrenceResponse(),
)

private fun TransactionDetails.recurrenceResponse(): ExpenseRecurrenceResponse? {
    val rule = recurringRule ?: return null
    val instanceDate = transaction.recurringInstanceDate ?: return null
    return ExpenseRecurrenceResponse(
        ruleId = rule.id ?: error("Recurring expense rule must be persisted before it can be returned"),
        startDate = rule.startDate,
        endDate = rule.endDate,
        instanceDate = instanceDate,
        description = RecurrenceDescriptionFormatter.describe(
            RecurrenceSchedule(rule.recurrenceFrequency, rule.recurrenceInterval),
            rule.endDate,
        ),
    )
}

data class ExpenseResponse(
    val id: Long,
    val trackingAccount: ExpenseTrackingAccountResponse,
    val category: ExpenseCategorySummaryResponse,
    val date: LocalDate,
    val amountMinor: Long,
    val notes: String?,
    val recurrence: ExpenseRecurrenceResponse?,
)

data class ExpenseRecurrenceResponse(
    val ruleId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val instanceDate: LocalDate,
    val description: String,
)

data class ExpenseTrackingAccountResponse(
    val id: Long,
    val name: String,
    val currency: String,
)

data class ExpenseCategorySummaryResponse(
    val id: Long,
    val name: String,
)
