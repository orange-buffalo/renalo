package io.orangebuffalo.renalo;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.orangebuffalo.renalo.test.ApiTestClient;
import io.orangebuffalo.renalo.test.IntegrationTestSupport;
import io.orangebuffalo.renalo.user.PasswordHasher;
import io.orangebuffalo.renalo.user.User;
import io.orangebuffalo.renalo.user.UserRepository;
import io.orangebuffalo.renalo.user.UserType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class UserManagementApiTest extends IntegrationTestSupport {
    @Inject
    EmbeddedServer server;

    @Inject
    UserRepository userRepository;

    @Inject
    PasswordHasher passwordHasher;

    @Test
    void requiresAdminForListingUsers() throws Exception {
        saveUser("alice", "password", UserType.USER);
        saveUser("admin", "password", UserType.ADMIN);

        String userToken = api().login("alice", "password");

        assertEquals(401, api().get("/api/users", null).statusCode());
        assertEquals(403, api().get("/api/users", userToken).statusCode());
    }

    @Test
    void listsUsersForAdminWithPagination() throws Exception {
        saveUser("bob", "password", UserType.USER);
        saveUser("admin", "password", UserType.ADMIN);
        saveUser("alice", "password", UserType.USER);

        String adminToken = api().login("admin", "password");

        HttpResponse<String> response = api().get("/api/users?page=0&size=2", adminToken);

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"page\":0"));
        assertTrue(body.contains("\"size\":2"));
        assertTrue(body.contains("\"totalElements\":3"));
        assertTrue(body.contains("\"totalPages\":2"));
        assertTrue(body.contains("\"username\":\"admin\""));
        assertTrue(body.contains("\"type\":\"ADMIN\""));
        assertTrue(body.contains("\"currentUser\":true"));
        assertTrue(body.contains("\"username\":\"alice\""));
        assertTrue(body.indexOf("\"username\":\"admin\"") < body.indexOf("\"username\":\"alice\""));
        assertEquals(-1, body.indexOf("\"username\":\"bob\""));
    }

    @Test
    void rejectsInvalidPagination() throws Exception {
        saveUser("admin", "password", UserType.ADMIN);
        String adminToken = api().login("admin", "password");

        assertEquals(400, api().get("/api/users?page=-1&size=10", adminToken).statusCode());
        assertEquals(400, api().get("/api/users?page=0&size=0", adminToken).statusCode());
        assertEquals(400, api().get("/api/users?page=0&size=101", adminToken).statusCode());
    }

    @Test
    void requiresAdminForDeletingUsers() throws Exception {
        User alice = saveUser("alice", "password", UserType.USER);
        saveUser("admin", "password", UserType.ADMIN);

        String userToken = api().login("alice", "password");

        assertEquals(401, api().delete("/api/users/%d".formatted(alice.getId()), null).statusCode());
        assertEquals(403, api().delete("/api/users/%d".formatted(alice.getId()), userToken).statusCode());
    }

    @Test
    void deletesAnotherUserForAdmin() throws Exception {
        User alice = saveUser("alice", "password", UserType.USER);
        saveUser("admin", "password", UserType.ADMIN);

        String adminToken = api().login("admin", "password");

        HttpResponse<String> response = api().delete("/api/users/%d".formatted(alice.getId()), adminToken);

        assertEquals(204, response.statusCode());
        assertNull(userRepository.findByUsername("alice"));
    }

    @Test
    void preventsAdminFromDeletingCurrentUser() throws Exception {
        User admin = saveUser("admin", "password", UserType.ADMIN);

        String adminToken = api().login("admin", "password");

        HttpResponse<String> response = api().delete("/api/users/%d".formatted(admin.getId()), adminToken);

        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"CURRENT_USER\""));
        assertEquals("admin", userRepository.findByUsername("admin").getUsername());
    }

    @Test
    void returnsNotFoundWhenDeletingMissingUser() throws Exception {
        saveUser("admin", "password", UserType.ADMIN);
        String adminToken = api().login("admin", "password");

        assertEquals(404, api().delete("/api/users/999999", adminToken).statusCode());
    }

    private User saveUser(String username, String password, UserType type) {
        return userRepository.save(new User(null, username, passwordHasher.hash(password), type));
    }

    private ApiTestClient api() {
        return new ApiTestClient(server);
    }
}
