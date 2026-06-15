package io.orangebuffalo.renalo.test;

import io.micronaut.context.annotation.Value;
import io.micronaut.security.token.generator.TokenGenerator;
import io.orangebuffalo.renalo.user.UserType;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;

@Singleton
public class TestAuthTokens {
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
        return tokenGenerator.generateToken(Map.of(
                "sub", username,
                "roles", List.of(type.name()),
                "userType", type.name(),
                "exp", testTimeProvider.now().plusSeconds(accessTokenExpirationSeconds).getEpochSecond()
        )).orElseThrow(() -> new IllegalStateException("JWT token could not be generated"));
    }
}
