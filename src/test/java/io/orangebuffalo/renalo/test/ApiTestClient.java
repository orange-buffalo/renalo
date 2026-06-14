package io.orangebuffalo.renalo.test;

import io.micronaut.runtime.server.EmbeddedServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ApiTestClient {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    private final EmbeddedServer server;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ApiTestClient(EmbeddedServer server) {
        this.server = server;
    }

    public String login(String username, String password) throws Exception {
        HttpResponse<String> response = postJson("/api/create-auth-token", """
                {"username":"%s","password":"%s"}
                """.formatted(username, password), null);

        assertEquals(200, response.statusCode());
        String token = extractToken(response.body());
        assertFalse(token.isBlank());
        return token;
    }

    public String extractToken(String responseBody) {
        var tokenMatcher = TOKEN_PATTERN.matcher(responseBody);
        assertEquals(true, tokenMatcher.find());
        return tokenMatcher.group(1);
    }

    public HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.getURL() + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> postJson(String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.getURL() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
