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
import io.orangebuffalo.renalo.user.UserRepository
import java.time.LocalDate

@Controller("/api/tracking/expenses")
@Secured(UserRoles.USER)
class ExpenseController(
    private val userRepository: UserRepository,
    private val expenseService: ExpenseService,
) {
    @Get
    fun listExpenses(authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return HttpResponse.ok(expenseService.listExpenses(user.id!!).map { it.toResponse() })
    }

    @Get("/{expenseId}")
    fun getExpense(expenseId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val expense = expenseService.findExpense(user.id!!, expenseId)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(expense.toResponse())
    }

    @Post
    fun createExpense(authentication: Authentication, @Body request: SaveExpenseRequest): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val expense = expenseService.createExpense(user.id!!, request)
            ?: return HttpResponse.badRequest<Any>()

        return HttpResponse.created(expense.toResponse())
    }

    @Patch("/{expenseId}")
    fun updateExpense(
        expenseId: Long,
        authentication: Authentication,
        @Body request: SaveExpenseRequest,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val existingExpense = expenseService.findExpense(user.id!!, expenseId)
            ?: return HttpResponse.notFound<Any>()
        val expense = expenseService.updateExpense(user.id!!, existingExpense.expense.id!!, request)
            ?: return HttpResponse.badRequest<Any>()

        return HttpResponse.ok(expense.toResponse())
    }

    @Delete("/{expenseId}")
    fun deleteExpense(expenseId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        if (!expenseService.deleteExpense(user.id!!, expenseId)) {
            return HttpResponse.notFound<Any>()
        }

        return HttpResponse.noContent<Any>()
    }
}

private fun ExpenseDetails.toResponse() = ExpenseResponse(
    id = expense.id ?: error("Expense must be persisted before it can be returned"),
    trackingAccount = ExpenseTrackingAccountResponse(
        id = account.id ?: error("Tracking account must be persisted before it can be returned"),
        name = account.name,
        currency = account.currency,
    ),
    category = ExpenseCategorySummaryResponse(
        id = category.id ?: error("Expense category must be persisted before it can be returned"),
        name = category.name,
    ),
    date = expense.date,
    amountMinor = expense.amountMinor,
    notes = expense.notes,
)

data class ExpenseResponse(
    val id: Long,
    val trackingAccount: ExpenseTrackingAccountResponse,
    val category: ExpenseCategorySummaryResponse,
    val date: LocalDate,
    val amountMinor: Long,
    val notes: String?,
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
