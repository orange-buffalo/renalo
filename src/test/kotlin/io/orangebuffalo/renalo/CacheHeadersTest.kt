package io.orangebuffalo.renalo

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.orangebuffalo.renalo.test.IntegrationTestSupport
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
class CacheHeadersTest : IntegrationTestSupport() {
    private val httpClient = HttpClient.newHttpClient()

    @Test
    fun indexAndAssetsHaveExpectedCacheHeaders() {
        val indexResponse = httpClient.send(
            HttpRequest.newBuilder(server.url.toURI()).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        indexResponse.statusCode().shouldBe(200)
        indexResponse.headers().firstValue("cache-control").orElseThrow()
            .shouldBe("no-store, no-cache, must-revalidate, max-age=0")

        val scriptPath = Regex("src=\"([^\"]*index-[^\"]*\\.js)\"")
            .find(indexResponse.body())
            ?.groupValues
            ?.get(1)
            ?: error("Expected index.html to reference a built JavaScript asset")
        indexResponse.body().shouldContain(scriptPath)

        val stylesheetPath = Regex("href=\"([^\"]*styles-[a-f0-9]{8}\\.css)\"")
            .find(indexResponse.body())
            ?.groupValues
            ?.get(1)
            ?: error("Expected index.html to reference a fingerprinted CSS asset")
        indexResponse.body().shouldContain(stylesheetPath)

        val assetResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(server.url.toString() + scriptPath)).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )

        assetResponse.statusCode().shouldBe(200)
        assetResponse.headers().firstValue("cache-control").orElseThrow()
            .shouldBe("public, max-age=31536000, immutable")

        val stylesheetResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(server.url.toString() + stylesheetPath)).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )

        stylesheetResponse.statusCode().shouldBe(200)
        stylesheetResponse.headers().firstValue("cache-control").orElseThrow()
            .shouldBe("public, max-age=31536000, immutable")
    }
}
