package io.orangebuffalo.renalo;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.orangebuffalo.renalo.test.IntegrationTestSupport;
import io.orangebuffalo.renalo.user.PasswordHasher;
import io.orangebuffalo.renalo.user.User;
import io.orangebuffalo.renalo.user.UserRepository;
import io.orangebuffalo.renalo.user.UserType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class AuthApiTest extends IntegrationTestSupport {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    @Inject
    EmbeddedServer server;

    @Inject
    UserRepository userRepository;

    @Inject
    PasswordHasher passwordHasher;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void rejectsInvalidCredentials() throws Exception {
        saveUser("alice", "correct-password", UserType.USER);

        HttpResponse<String> response = postJson("/api/create-auth-token", """
                {"username":"alice","password":"wrong-password"}
                """, null);

        assertEquals(401, response.statusCode());
    }

    @Test
    void issuesTokenAndReturnsProfile() throws Exception {
        saveUser("alice", "correct-password", UserType.USER);

        String token = login("alice", "correct-password");
        HttpResponse<String> profileResponse = get("/api/profile", token);

        assertEquals(200, profileResponse.statusCode());
        assertEquals(true, profileResponse.body().contains("\"username\":\"alice\""));
        assertEquals(true, profileResponse.body().contains("\"type\":\"USER\""));
    }

    @Test
    void requiresTokenForProfile() throws Exception {
        HttpResponse<String> response = get("/api/profile", null);

        assertEquals(401, response.statusCode());
    }

    @Test
    void enforcesRoleChecks() throws Exception {
        saveUser("alice", "user-password", UserType.USER);
        saveUser("admin", "admin-password", UserType.ADMIN);

        String userToken = login("alice", "user-password");
        String adminToken = login("admin", "admin-password");

        assertEquals(200, get("/api/tracking", userToken).statusCode());
        assertEquals(403, get("/api/user-management", userToken).statusCode());
        assertEquals(200, get("/api/user-management", adminToken).statusCode());
    }

    private void saveUser(String username, String password, UserType type) {
        userRepository.save(new User(null, username, passwordHasher.hash(password), type));
    }

    private String login(String username, String password) throws Exception {
        HttpResponse<String> response = postJson("/api/create-auth-token", """
                {"username":"%s","password":"%s"}
                """.formatted(username, password), null);

        assertEquals(200, response.statusCode());
        var tokenMatcher = TOKEN_PATTERN.matcher(response.body());
        assertEquals(true, tokenMatcher.find());
        String token = tokenMatcher.group(1);
        assertFalse(token.isBlank());
        return token;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.getURL() + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.getURL() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
