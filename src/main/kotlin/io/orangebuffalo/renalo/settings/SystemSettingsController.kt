package io.orangebuffalo.renalo.settings

import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule

@Controller("/api/system-settings")
@Secured(SecurityRule.IS_AUTHENTICATED)
class SystemSettingsController(
    @param:Value("\${renalo.public-url}") private val publicUrl: String,
) {
    @Get
    fun getSettings(): HttpResponse<SystemSettingsResponse> {
        return HttpResponse.ok(SystemSettingsResponse(publicUrl = publicUrl))
    }
}

data class SystemSettingsResponse(
    val publicUrl: String,
)
