package com.livenotification.delivery.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliverySchemaConstraintTest {

    private static final String JDBC_URL =
        System.getenv().getOrDefault("SCHEMA_TEST_JDBC_URL",
            "jdbc:postgresql://localhost:5432/notification");
    private static final String DB_USER =
        System.getenv().getOrDefault("SCHEMA_TEST_JDBC_USER", "notification");
    private static final String DB_PASS =
        System.getenv().getOrDefault("SCHEMA_TEST_JDBC_PASSWORD", "notification");

    @Test
    void deliveryTable_rejectsSentAtStateMismatch() {
        DriverManagerDataSource ds = new DriverManagerDataSource(JDBC_URL, DB_USER, DB_PASS);
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .baselineOnMigrate(true)
            .load()
            .clean();

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .baselineOnMigrate(true)
            .load()
            .migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(ds);
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        UUID notificationId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO notification (id, event_id, recipient_id, type, payload, created_at, updated_at)
            VALUES (?, 'schema-e1', 'schema-u1', 'PAYMENT_CONFIRMED', '{}'::jsonb, ?, ?)
            ON CONFLICT DO NOTHING
            """, notificationId, Timestamp.from(now), Timestamp.from(now));

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO delivery (id, notification_id, channel, state, attempt_count, sent_at, created_at, updated_at)
            VALUES (?, ?, 'EMAIL', 'PENDING', 0, ?, ?, ?)
            """, UUID.randomUUID(), notificationId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO delivery (id, notification_id, channel, state, attempt_count, created_at, updated_at)
            VALUES (?, ?, 'IN_APP', 'SENT', 1, ?, ?)
            """, UUID.randomUUID(), notificationId, Timestamp.from(now), Timestamp.from(now)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
