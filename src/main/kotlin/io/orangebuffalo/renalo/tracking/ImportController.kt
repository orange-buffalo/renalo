package io.orangebuffalo.renalo.tracking

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.user.UserRepository

@Controller("/api/import")
@Secured(UserRoles.USER)
class ImportController(
    private val userRepository: UserRepository,
    private val toshlImportService: ToshlImportService,
) {
    @Post("/toshl")
    fun importToshl(authentication: Authentication, @Body request: ToshlImportRequest): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        if (request.csvContent.isBlank()) {
            return HttpResponse.badRequest(ToshlImportErrorResponse("CSV_EMPTY", "CSV file is empty"))
        }

        return try {
            HttpResponse.ok(toshlImportService.import(user.id!!, request.csvContent))
        } catch (exception: ToshlImportException) {
            HttpResponse.badRequest(ToshlImportErrorResponse("CSV_INVALID", exception.message ?: "CSV file is invalid"))
        }
    }
}

data class ToshlImportErrorResponse(
    val code: String,
    val details: String,
)
