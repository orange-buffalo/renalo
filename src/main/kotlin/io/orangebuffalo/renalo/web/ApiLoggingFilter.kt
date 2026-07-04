package io.orangebuffalo.renalo.web

import io.micronaut.core.async.publisher.Publishers
import io.micronaut.http.HttpAttributes
import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Filter
import io.micronaut.http.filter.HttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import io.micronaut.security.authentication.Authentication
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory

@Filter("/api/**")
class ApiLoggingFilter : HttpServerFilter {

    override fun getOrder(): Int = 49000

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilter(request: HttpRequest<*>, chain: ServerFilterChain): Publisher<MutableHttpResponse<*>> {
        val authentication = request.getAttribute(HttpAttributes.PRINCIPAL, Authentication::class.java).orElse(null)
        val username = authentication?.name ?: "anonymous"
        val method = request.method
        val path = request.path

        log.info("Started {} {} by user {}", method, path, username)

        return Publishers.map(chain.proceed(request)) { response ->
            log.info("Finished {} {} by user {} -> {}", method, path, username, response.status.code)
            response
        }
    }
}
