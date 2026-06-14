package io.orangebuffalo.renalo.web

import io.micronaut.core.async.publisher.Publishers
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Filter
import io.micronaut.http.filter.HttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import org.reactivestreams.Publisher

@Filter("/assets/**")
class StaticAssetCacheFilter : HttpServerFilter {
    override fun doFilter(request: HttpRequest<*>, chain: ServerFilterChain): Publisher<MutableHttpResponse<*>> =
        Publishers.map(chain.proceed(request)) { response ->
            response.header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
        }
}
