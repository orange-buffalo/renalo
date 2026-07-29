package io.orangebuffalo.renalo.tracking

import com.fasterxml.jackson.annotation.JsonInclude
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

@Controller("/api/tracking/dashboard/chart-presets")
@Secured(UserRoles.USER)
class DashboardChartPresetController(
    private val userRepository: UserRepository,
    private val presetService: DashboardChartPresetService,
) {
    @Get
    fun list(authentication: Authentication): HttpResponse<*> = withUser(authentication) { userId ->
        HttpResponse.ok(
            DashboardChartPresetsResponse(presetService.listPresets(userId).map { it.toResponse() }),
        )
    }

    @Post("/{transactionType}")
    fun create(
        transactionType: TransactionType,
        @Body request: SaveDashboardChartPresetRequest,
        authentication: Authentication,
    ): HttpResponse<*> = withUser(authentication) { userId ->
        when (val result = presetService.createPreset(userId, transactionType, request)) {
            is SaveDashboardChartPresetResult.Saved -> HttpResponse.created(result.preset.toResponse())
            SaveDashboardChartPresetResult.BadRequest -> HttpResponse.badRequest<Any>()
            SaveDashboardChartPresetResult.NotFound -> error("Create cannot return NotFound")
        }
    }

    @Patch("/{transactionType}/{presetId}")
    fun update(
        transactionType: TransactionType,
        presetId: Long,
        @Body request: SaveDashboardChartPresetRequest,
        authentication: Authentication,
    ): HttpResponse<*> = withUser(authentication) { userId ->
        when (val result = presetService.updatePreset(userId, transactionType, presetId, request)) {
            is SaveDashboardChartPresetResult.Saved -> HttpResponse.ok(result.preset.toResponse())
            SaveDashboardChartPresetResult.BadRequest -> HttpResponse.badRequest<Any>()
            SaveDashboardChartPresetResult.NotFound -> HttpResponse.notFound<Any>()
        }
    }

    @Post("/{transactionType}/active")
    fun setActive(
        transactionType: TransactionType,
        @Body request: SetActiveDashboardChartPresetRequest,
        authentication: Authentication,
    ): HttpResponse<*> = withUser(authentication) { userId ->
        when (presetService.setActivePreset(userId, transactionType, request.presetId)) {
            SetActiveDashboardChartPresetResult.Updated -> HttpResponse.noContent<Any>()
            SetActiveDashboardChartPresetResult.NotFound -> HttpResponse.notFound<Any>()
        }
    }

    @Delete("/{transactionType}/{presetId}")
    fun delete(
        transactionType: TransactionType,
        presetId: Long,
        authentication: Authentication,
    ): HttpResponse<*> = withUser(authentication) { userId ->
        when (presetService.deletePreset(userId, transactionType, presetId)) {
            DeleteDashboardChartPresetResult.Deleted -> HttpResponse.noContent<Any>()
            DeleteDashboardChartPresetResult.NotFound -> HttpResponse.notFound<Any>()
        }
    }

    private fun withUser(authentication: Authentication, action: (Long) -> HttpResponse<*>): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        return action(user.id!!)
    }
}

data class SetActiveDashboardChartPresetRequest(val presetId: Long?)

data class DashboardChartPresetsResponse(
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val presets: List<DashboardChartPresetResponse>,
)

data class DashboardChartPresetResponse(
    val id: Long,
    val name: String,
    val transactionType: TransactionType,
    val categoryFilterMode: DashboardChartFilterMode,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val categoryIds: List<Long>,
    val accountFilterMode: DashboardChartFilterMode,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val accountIds: List<Long>,
    val granularity: TransactionTimeSeriesGranularity,
    val isActive: Boolean,
)

private fun DashboardChartPreset.toResponse() = DashboardChartPresetResponse(
    id = id ?: error("Dashboard chart preset must be persisted before it can be returned"),
    name = name,
    transactionType = transactionType,
    categoryFilterMode = categoryFilterMode,
    categoryIds = categoryIds,
    accountFilterMode = accountFilterMode,
    accountIds = accountIds,
    granularity = granularity,
    isActive = isActive,
)
