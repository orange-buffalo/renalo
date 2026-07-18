package io.orangebuffalo.renalo.tracking

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.user.UserRepository

@Controller("/api/tracking/accounts")
@Secured(UserRoles.USER)
class TrackingAccountController(
    private val userRepository: UserRepository,
    private val trackingAccountService: TrackingAccountService,
) {
    @Get
    fun listAccounts(
        authentication: Authentication,
        @QueryValue(defaultValue = "false") includeArchived: Boolean,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return HttpResponse.ok(
            trackingAccountService.listAccounts(user.id!!, includeArchived).map { it.toResponse() },
        )
    }

    @Get("/{accountId}")
    fun getAccount(accountId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val account = trackingAccountService.findAccount(user.id!!, accountId)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(account.toResponse())
    }

    @Post
    fun createAccount(
        authentication: Authentication,
        @Body request: SaveTrackingAccountRequest,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val account = trackingAccountService.createAccount(user.id!!, request)
            ?: return HttpResponse.badRequest<Any>()

        return HttpResponse.created(account.toResponse())
    }

    @Patch("/{accountId}")
    fun updateAccount(
        accountId: Long,
        authentication: Authentication,
        @Body request: SaveTrackingAccountRequest,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val existingAccount = trackingAccountService.findAccount(user.id!!, accountId)
            ?: return HttpResponse.notFound<Any>()
        val account = trackingAccountService.updateAccount(user.id!!, existingAccount.id!!, request)
            ?: return HttpResponse.badRequest<Any>()

        return HttpResponse.ok(account.toResponse())
    }

    @Get("/{accountId}/merge-summary")
    fun getMergeSummary(accountId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val summary = trackingAccountService.getMergeSummary(user.id!!, accountId)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(summary.toResponse())
    }

    @Post("/{accountId}/merge")
    fun mergeAccount(
        accountId: Long,
        authentication: Authentication,
        @Body request: MergeTrackingAccountRequest,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return when (trackingAccountService.mergeAccount(user.id!!, accountId, request)) {
            TrackingAccountMergeResult.MERGED -> HttpResponse.noContent<Any>()
            TrackingAccountMergeResult.NOT_FOUND -> HttpResponse.notFound<Any>()
            TrackingAccountMergeResult.INVALID_TARGET -> HttpResponse.badRequest<Any>()
        }
    }

    @Post("/{accountId}/archive")
    fun archiveAccount(accountId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val account = trackingAccountService.archiveAccount(user.id!!, accountId)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(account.toResponse())
    }

    @Post("/{accountId}/unarchive")
    fun unarchiveAccount(accountId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val account = trackingAccountService.unarchiveAccount(user.id!!, accountId)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(account.toResponse())
    }
}

private fun TrackingAccount.toResponse() = TrackingAccountResponse(
    id = id ?: error("Tracking account must be persisted before it can be returned"),
    name = name,
    currency = currency,
    initialBalanceMinor = initialBalanceMinor,
    isDefault = isDefault,
    archived = archived,
)

data class TrackingAccountResponse(
    val id: Long,
    val name: String,
    val currency: String,
    val initialBalanceMinor: Long,
    val isDefault: Boolean,
    val archived: Boolean,
)

private fun TrackingAccountOverview.toResponse() = TrackingAccountOverviewResponse(
    id = account.id ?: error("Tracking account must be persisted before it can be returned"),
    name = account.name,
    currency = account.currency,
    initialBalanceMinor = account.initialBalanceMinor,
    isDefault = account.isDefault,
    archived = account.archived,
    entriesCount = entriesCount,
)

data class TrackingAccountOverviewResponse(
    val id: Long,
    val name: String,
    val currency: String,
    val initialBalanceMinor: Long,
    val isDefault: Boolean,
    val archived: Boolean,
    val entriesCount: Long,
)

private fun TrackingAccountMergeSummary.toResponse() = TrackingAccountMergeSummaryResponse(
    sourceAccount = sourceAccount.toResponse(),
    expensesCount = expensesCount,
    incomesCount = incomesCount,
    transfersCount = transfersCount,
    targetAccounts = targetAccounts.map { it.toResponse() },
)

data class TrackingAccountMergeSummaryResponse(
    val sourceAccount: TrackingAccountResponse,
    val expensesCount: Long,
    val incomesCount: Long,
    val transfersCount: Long,
    val targetAccounts: List<TrackingAccountResponse>,
)
