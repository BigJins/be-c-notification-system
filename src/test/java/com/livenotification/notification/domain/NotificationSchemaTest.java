package com.livenotification.notification.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema invariant test: confirms the {@code notification} table has no {@code state} column.
 *
 * <p>CLAUDE.md invariant: "NotificationState 컬럼 없음 — overall state는 delivery aggregate 로 derive."
 *
 * <p>Connects to the dev Postgres container (docker-compose, localhost:5432) and applies Flyway
 * idempotently, then inspects {@code information_schema} via JDBC metadata.
 *
 * <p>Override coordinates via env vars {@code SCHEMA_TEST_JDBC_URL} / {@code _USER} / {@code _PASSWORD}
 * if running in a different environment.
 */
class NotificationSchemaTest {

    private static final String JDBC_URL =
        System.getenv().getOrDefault("SCHEMA_TEST_JDBC_URL",
            "jdbc:postgresql://localhost:5432/notification");
    private static final String DB_USER =
        System.getenv().getOrDefault("SCHEMA_TEST_JDBC_USER", "notification");
    private static final String DB_PASS =
        System.getenv().getOrDefault("SCHEMA_TEST_JDBC_PASSWORD", "notification");

    @Test
    void notificationTable_hasNoStateColumn() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(JDBC_URL, DB_USER, DB_PASS);

        // Apply Flyway idempotently (baseline-on-migrate so existing schema is tolerated)
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        // Inspect notification table columns via JDBC metadata
        try (Connection conn = ds.getConnection();
             ResultSet rs = conn.getMetaData().getColumns(null, null, "notification", null)) {
            Set<String> columns = new HashSet<>();
            while (rs.next()) columns.add(rs.getString("COLUMN_NAME").toLowerCase());
            assertThat(columns)
                .as("CLAUDE.md invariant: NotificationState 컬럼 없음 — overall state는 delivery aggregate 로 derive")
                .doesNotContain("state", "notification_state");
        }
    }
}
