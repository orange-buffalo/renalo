package io.orangebuffalo.renalo;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.orangebuffalo.renalo.test.ApiTestClient;
import io.orangebuffalo.renalo.test.IntegrationTestSupport;
import io.orangebuffalo.renalo.test.TestTimeProvider;
import io.orangebuffalo.renalo.user.PasswordHasher;
import io.orangebuffalo.renalo.user.User;
import io.orangebuffalo.renalo.user.UserRepository;
import io.orangebuffalo.renalo.user.UserType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class AuthApiTest extends IntegrationTestSupport {
    @Inject
    EmbeddedServer server;

    @Inject
    UserRepository userRepository;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    TestTimeProvider testTimeProvider;

    @BeforeEach
    void resetBusinessTime() {
        testTimeProvider.reset();
    }

    @Test
    void rejectsInvalidCredentials() throws Exception {
        saveUser("alice", "correct-password", UserType.USER);

        HttpResponse<String> response = api().postJson("/api/create-auth-token", """
                {"username":"alice","password":"wrong-password"}
                """, null);

        assertEquals(401, response.statusCode());
    }

    @Test
    void issuesTokenAndReturnsProfile() throws Exception {
        saveUser("alice", "correct-password", UserType.USER);

        String token = api().login("alice", "correct-password");
        HttpResponse<String> profileResponse = api().get("/api/profile", token);

        assertEquals(200, profileResponse.statusCode());
        assertTrue(profileResponse.body().contains("\"username\":\"alice\""));
        assertTrue(profileResponse.body().contains("\"type\":\"USER\""));
    }

    @Test
    void requiresTokenForProfile() throws Exception {
        HttpResponse<String> response = api().get("/api/profile", null);

        assertEquals(401, response.statusCode());
    }

    @Test
    void enforcesRoleChecks() throws Exception {
        saveUser("alice", "user-password", UserType.USER);
        saveUser("admin", "admin-password", UserType.ADMIN);

        String userToken = api().login("alice", "user-password");
        String adminToken = api().login("admin", "admin-password");

        assertEquals(200, api().get("/api/tracking", userToken).statusCode());
        assertEquals(403, api().get("/api/user-management", userToken).statusCode());
        assertEquals(200, api().get("/api/user-management", adminToken).statusCode());
    }

    @Test
    void issuesTokenWithConfiguredExpiration() throws Exception {
        saveUser("alice", "correct-password", UserType.USER);

        String token = api().login("alice", "correct-password");

        String payloadJson = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        long expiration = Long.parseLong(payloadJson.replaceAll(".*\"exp\":([0-9]+).*", "$1"));
        assertEquals(TestTimeProvider.DEFAULT_TIME.plusSeconds(1800).getEpochSecond(), expiration);
    }

    private void saveUser(String username, String password, UserType type) {
        userRepository.save(new User(null, username, passwordHasher.hash(password), type));
    }

    private ApiTestClient api() {
        return new ApiTestClient(server);
    }
}
