package io.orangebuffalo.renalo.test;

import com.microsoft.playwright.Page;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

@ExtendWith({DatabaseCleanupExtension.class, PlaywrightExtension.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class IntegrationTestSupport implements TestPropertyProvider {
    @Inject
    protected EmbeddedServer server;

    @Inject
    protected TestTimeProvider testTimeProvider;

    @BeforeEach
    void resetBusinessTime() {
        testTimeProvider.reset();
    }

    @Override
    public Map<String, String> getProperties() {
        PostgreSQLContainer<?> postgres = PostgresTestContainer.getContainer();
        return Map.of(
                "datasources.default.url", postgres.getJdbcUrl(),
                "datasources.default.username", postgres.getUsername(),
                "datasources.default.password", postgres.getPassword(),
                "datasources.default.driver-class-name", "org.postgresql.Driver",
                "renalo.login-bruteforce-delay", "PT0.01S",
                "renalo.activation-token.delay", "PT0.01S"
        );
    }

    protected ApiTestClient api() {
        return new ApiTestClient(server);
    }

    protected void setStoredToken(Page page, String token) {
        page.addInitScript("""
                if (!window.sessionStorage.getItem('renalo.testStoredTokenSeeded')) {
                    window.localStorage.setItem('renalo.authToken', '%s');
                    window.sessionStorage.setItem('renalo.testStoredTokenSeeded', 'true');
                }
                """.formatted(token));
    }
}
