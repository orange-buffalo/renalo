package io.orangebuffalo.renalo;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.orangebuffalo.renalo.test.IntegrationTestSupport;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
class CacheHeadersTest extends IntegrationTestSupport {
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("src=\"([^\"]*index-[^\"]*\\.js)\"");

    @Inject
    EmbeddedServer server;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void indexAndAssetsHaveExpectedCacheHeaders() throws Exception {
        HttpResponse<String> indexResponse = httpClient.send(
                HttpRequest.newBuilder(server.getURL().toURI()).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, indexResponse.statusCode());
        assertEquals(
                "no-store, no-cache, must-revalidate, max-age=0",
                indexResponse.headers().firstValue("cache-control").orElseThrow()
        );

        var scriptMatcher = SCRIPT_PATTERN.matcher(indexResponse.body());
        assertTrue(scriptMatcher.find(), "Expected index.html to reference a built JavaScript asset");

        HttpResponse<Void> assetResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(server.getURL() + scriptMatcher.group(1))).GET().build(),
                HttpResponse.BodyHandlers.discarding()
        );

        assertEquals(200, assetResponse.statusCode());
        assertEquals(
                "public, max-age=31536000, immutable",
                assetResponse.headers().firstValue("cache-control").orElseThrow()
        );
    }
}
