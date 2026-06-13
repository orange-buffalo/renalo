package app.renalo.web

import io.micronaut.core.io.ResourceResolver
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.server.types.files.StreamedFile

@Controller
class SpaController(private val resourceResolver: ResourceResolver) {
    @Get(uri = "/", produces = [MediaType.TEXT_HTML])
    fun index(): HttpResponse<StreamedFile> = indexResponse()

    @Get(uri = "/{path:^(?!api/|assets/|.*\\..*$).+}", produces = [MediaType.TEXT_HTML])
    fun route(path: String): HttpResponse<StreamedFile> = indexResponse()

    private fun indexResponse(): HttpResponse<StreamedFile> {
        val resource = resourceResolver.getResource("classpath:public/index.html")
            .orElseThrow { IllegalStateException("UI index.html was not found on the classpath") }
        return HttpResponse.ok(StreamedFile(resource.openStream(), MediaType.TEXT_HTML_TYPE))
    }
}
