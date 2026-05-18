package com.livenotification.idempotency.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyRecordInvariantTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC);

    // Valid SHA-256 hex (64 lowercase hex chars)
    private static final String VALID_HASH =
        "a3f1b2c4d5e6f7890123456789abcdef" +
        "a3f1b2c4d5e6f7890123456789abcdef";

    @Test
    void expiresAt_equalsCreatedAt_plus_ttl() {
        // Invariant 1: expires_at = created_at + ttl.
        IdempotencyKey key = new IdempotencyKey("test-key-001");
        RequestHash hash = new RequestHash(VALID_HASH);
        UUID targetId = UUID.randomUUID();
        Duration ttl = Duration.ofHours(24);

        IdempotencyRecord record = IdempotencyRecord.create(key, hash, targetId, fixedClock, ttl);

        assertThat(record.getCreatedAt()).isEqualTo(fixedClock.instant());
        assertThat(record.getExpiresAt())
            .as("expiresAt must be createdAt + ttl")
            .isEqualTo(record.getCreatedAt().plus(ttl));
        assertThat(Duration.between(record.getCreatedAt(), record.getExpiresAt()))
            .isEqualTo(ttl);
    }

    @Test
    void requestHash_rejectsInvalidFormat_acceptsValidHex64() {
        // Invariant 2: RequestHash regex validation.
        // Invalid hash → IllegalArgumentException.
        assertThatThrownBy(() -> new RequestHash("not-hex"))
            .isInstanceOf(IllegalArgumentException.class);

        // Null → IllegalArgumentException.
        assertThatThrownBy(() -> new RequestHash(null))
            .isInstanceOf(IllegalArgumentException.class);

        // Too short (63 chars) → IllegalArgumentException.
        assertThatThrownBy(() -> new RequestHash("a".repeat(63)))
            .isInstanceOf(IllegalArgumentException.class);

        // Too long (65 chars) → IllegalArgumentException.
        assertThatThrownBy(() -> new RequestHash("a".repeat(65)))
            .isInstanceOf(IllegalArgumentException.class);

        // Uppercase hex → IllegalArgumentException (must be lowercase).
        assertThatThrownBy(() -> new RequestHash("A".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);

        // Valid: exactly 64 lowercase hex chars → no exception.
        RequestHash valid = new RequestHash(VALID_HASH);
        assertThat(valid.value()).hasSize(64);
        assertThat(valid.value()).matches("[0-9a-f]{64}");
    }
}
