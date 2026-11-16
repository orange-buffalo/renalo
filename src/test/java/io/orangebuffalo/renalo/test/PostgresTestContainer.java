package io.orangebuffalo.renalo.test;

import org.testcontainers.containers.PostgreSQLContainer;

public final class PostgresTestContainer {
    // PostgreSQL 18.7-bookworm; Testcontainers requires digest-only image references.
    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>("postgres@sha256:ece55f7868da684e400f82f1030a050718adbdd65c23aa500c486f6e2950dc0f")
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
