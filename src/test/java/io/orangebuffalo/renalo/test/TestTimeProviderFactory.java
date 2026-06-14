package io.orangebuffalo.renalo.test;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.orangebuffalo.renalo.time.SystemTimeProvider;
import jakarta.inject.Singleton;

@Factory
class TestTimeProviderFactory {
    @Singleton
    @Replaces(SystemTimeProvider.class)
    TestTimeProvider timeProvider() {
        return new TestTimeProvider();
    }
}
