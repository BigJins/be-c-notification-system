package com.livenotification.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_record")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@EqualsAndHashCode(of = "idempotencyKey")
@ToString(of = {"idempotencyKey", "targetId"})
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", nullable = false)
    @Getter(AccessLevel.NONE)
    private String idempotencyKey;

    @Column(name = "request_hash", updatable = false, nullable = false)
    @Getter(AccessLevel.NONE)
    private String requestHash;

    @Column(name = "target_id", updatable = false, nullable = false)
    private UUID targetId;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", updatable = false, nullable = false)
    private Instant expiresAt;

    public IdempotencyKey getIdempotencyKey() {
        return new IdempotencyKey(idempotencyKey);
    }

    public RequestHash getRequestHash() {
        return new RequestHash(requestHash);
    }

    public static IdempotencyRecord create(IdempotencyKey key, RequestHash hash, UUID targetId,
                                           Clock clock, Duration ttl) {
        Instant now = clock.instant();
        IdempotencyRecord record = new IdempotencyRecord();
        record.idempotencyKey = key.value();
        record.requestHash = hash.value();
        record.targetId = targetId;
        record.createdAt = now;
        record.expiresAt = now.plus(ttl);
        return record;
    }

    public boolean matches(RequestHash other) {
        return this.requestHash.equals(other.value());
    }
}
