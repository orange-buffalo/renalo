package io.orangebuffalo.renalo.test;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class DatabaseCleanupExtension implements BeforeEachCallback, AfterEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        cleanDatabase();
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        cleanDatabase();
    }

    private static void cleanDatabase() throws SQLException {
        PostgreSQLContainer<?> postgres = PostgresTestContainer.getContainer();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        )) {
            List<String> tables = new ArrayList<>();
            try (ResultSet resultSet = connection.createStatement().executeQuery("""
                    SELECT quote_ident(table_schema) || '.' || quote_ident(table_name)
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_type = 'BASE TABLE'
                      AND table_name <> 'flyway_schema_history'
                    """)) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString(1));
                }
            }

            if (!tables.isEmpty()) {
                try (var statement = connection.createStatement()) {
                    statement.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
                }
            }
        }
    }
}
