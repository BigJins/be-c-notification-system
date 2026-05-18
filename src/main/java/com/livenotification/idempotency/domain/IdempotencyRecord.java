package com.livenotification.idempotency.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "idempotency_record")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter @EqualsAndHashCode(of = "idempotencyKey") @ToString(of = {"idempotencyKey", "targetId"})
public class IdempotencyRecord {
    @Id @Column(name = "idempotency_key") private IdempotencyKey idempotencyKey;
    @Column(name = "request_hash", updatable = false, nullable = false) private RequestHash requestHash;
    @Column(name = "target_id",   updatable = false, nullable = false) private UUID targetId;  // raw UUID — module-independent
    @Column(name = "created_at",  updatable = false, nullable = false) private Instant createdAt;
    @Column(name = "expires_at",  updatable = false, nullable = false) private Instant expiresAt;

    public static IdempotencyRecord create(IdempotencyKey key, RequestHash hash, UUID targetId,
                                            Clock clock, Duration ttl) {
        Instant now = clock.instant();
        IdempotencyRecord r = new IdempotencyRecord();
        r.idempotencyKey = key; r.requestHash = hash; r.targetId = targetId;
        r.createdAt = now; r.expiresAt = now.plus(ttl);
        return r;
    }

    public boolean matches(RequestHash other) { return this.requestHash.equals(other); }
}
