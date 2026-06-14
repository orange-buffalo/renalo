package io.orangebuffalo.renalo.web

import io.micronaut.core.io.ResourceResolver
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.server.types.files.StreamedFile
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule

@Controller
@Secured(SecurityRule.IS_ANONYMOUS)
class SpaController(private val resourceResolver: ResourceResolver) {
    @Get(uri = "/", produces = [MediaType.TEXT_HTML])
    fun index(): HttpResponse<StreamedFile> = indexResponse()

    @Get(uri = "/{path:^(?!api/|assets/|.*\\..*$).+}", produces = [MediaType.TEXT_HTML])
    fun route(path: String): HttpResponse<StreamedFile> = indexResponse()

    private fun indexResponse(): HttpResponse<StreamedFile> {
        val resource = resourceResolver.getResource("classpath:public/index.html")
            .orElseThrow { IllegalStateException("UI index.html was not found on the classpath") }
        return HttpResponse.ok(StreamedFile(resource.openStream(), MediaType.TEXT_HTML_TYPE))
            .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(HttpHeaders.EXPIRES, "0")
    }
}
