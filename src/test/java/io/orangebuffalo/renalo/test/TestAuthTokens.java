package io.orangebuffalo.renalo.test;

import io.micronaut.context.annotation.Value;
import io.micronaut.security.token.generator.TokenGenerator;
import io.orangebuffalo.renalo.user.UserType;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Singleton
public class TestAuthTokens {
    private static final Instant EXPIRED_TOKEN_TIME = Instant.parse("2020-06-14T08:00:00Z");

    private final TokenGenerator tokenGenerator;
    private final TestTimeProvider testTimeProvider;
    private final long accessTokenExpirationSeconds;

    public TestAuthTokens(
            TokenGenerator tokenGenerator,
            TestTimeProvider testTimeProvider,
            @Value("${renalo.auth.access-token-expiration-seconds}") long accessTokenExpirationSeconds
    ) {
        this.tokenGenerator = tokenGenerator;
        this.testTimeProvider = testTimeProvider;
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
    }

    public String issueToken(String username, UserType type) {
        return issueToken(username, type, testTimeProvider.now().plusSeconds(accessTokenExpirationSeconds));
    }

    public String issueExpiredToken(String username, UserType type) {
        return issueToken(username, type, EXPIRED_TOKEN_TIME);
    }

    public String issueToken(String username, UserType type, Instant expiresAt) {
        return tokenGenerator.generateToken(Map.of(
                "sub", username,
                "roles", List.of(type.name()),
                "userType", type.name(),
                "exp", expiresAt.getEpochSecond()
        )).orElseThrow(() -> new IllegalStateException("JWT token could not be generated"));
    }
}
