package com.livenotification.idempotency.application;

import com.livenotification.global.config.NotificationProperties;
import com.livenotification.idempotency.domain.IdempotencyKey;
import com.livenotification.idempotency.domain.IdempotencyRecord;
import com.livenotification.idempotency.domain.RequestHash;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotencyService {
    private final IdempotencyRepository repository;
    private final NotificationProperties properties;
    private final Clock clock;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Look up currently-valid record only (TTL filter).
     * INSERT path is the separate {@code persist(...)} / {@code persistIfAbsent(...)} methods.
     */
    @Transactional(readOnly = true)
    public IdempotencyResult lookupCurrent(IdempotencyKey key, RequestHash hash) {
        Instant now = clock.instant();
        return repository.findById(key)
            .filter(r -> r.getExpiresAt().isAfter(now))
            .<IdempotencyResult>map(r -> r.matches(hash)
                ? new IdempotencyResult.HitSameHash(r.getTargetId())
                : new IdempotencyResult.HitDifferentHash())
            .orElse(new IdempotencyResult.Miss());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void persist(IdempotencyKey key, RequestHash hash, UUID targetId, Duration ttl) {
        IdempotencyRecord r = IdempotencyRecord.create(key, hash, targetId, clock, ttl);
        repository.save(r);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void persistIfAbsent(IdempotencyKey key, RequestHash hash, UUID targetId, Duration ttl) {
        Instant now = clock.instant();
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("key", key.value())
            .addValue("hash", hash.value())
            .addValue("targetId", targetId)
            .addValue("createdAt", Timestamp.from(now))
            .addValue("expiresAt", Timestamp.from(now.plus(ttl)));
        jdbcTemplate.update("""
            INSERT INTO idempotency_record (idempotency_key, request_hash, target_id, created_at, expires_at)
            VALUES (:key, :hash, :targetId, :createdAt, :expiresAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, params);
    }
}
