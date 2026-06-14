package io.orangebuffalo.renalo.test;

import org.testcontainers.containers.PostgreSQLContainer;

public final class PostgresTestContainer {
    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>("postgres:18")
            .withDatabaseName("renalo_test")
            .withUsername("renalo")
            .withPassword("renalo");

    private PostgresTestContainer() {
    }

    public static PostgreSQLContainer<?> getContainer() {
        CONTAINER.start();
        return CONTAINER;
    }
}
