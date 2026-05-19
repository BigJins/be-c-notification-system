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

/**
 * Idempotency record store with a deliberate two-phase caller contract.
 *
 * <h2>Caller contract</h2>
 * Use this service in exactly two phases per registration transaction:
 *
 * <ol>
 *   <li><b>Gate phase</b> — call {@link #checkOutcome(IdempotencyKey, RequestHash)} first to
 *       decide whether to replay (HitSameHash), reject as 409 (HitDifferentHash), or proceed
 *       with the work (Miss). The check is read-only and TTL-filtered in Java.</li>
 *   <li><b>Bind phase</b> — after the target work has produced a {@code targetId} (e.g. the
 *       persisted {@code notification.id}), call {@link #bind(IdempotencyKey, RequestHash, UUID, Duration)}
 *       to atomically record the K → target mapping for future replays.</li>
 * </ol>
 *
 * Phases 1 and 3 of {@code docs/document.md} §4 sit on either side of the notification
 * insert/load, so the two-method shape is structurally required — they cannot be folded
 * into one call because the {@code targetId} only exists between them.
 *
 * <h2>Race semantics (ADR-0002)</h2>
 * Concurrent requests with the <i>same</i> {@code IdempotencyKey} converge correctly:
 *
 * <ul>
 *   <li><b>Same K + same H concurrent</b> — both pass the gate (both see Miss), both call
 *       {@code bind}; the PG unique constraint on {@code idempotency_key} ensures only the
 *       first wins, and the {@code WHERE expires_at &lt;= EXCLUDED.created_at} clause in
 *       {@code bind} preserves that winner against late expiries. All concurrent callers
 *       end up bound to the same {@code targetId} because the underlying notification's
 *       {@code uq_notification_event} converges them to one row.</li>
 *   <li><b>Same K + different H concurrent</b> — both pass the gate (both see Miss), both
 *       produce distinct notifications (distinct event_ids), both call {@code bind};
 *       <b>first-write-wins</b>: the first {@code bind} succeeds, the second is silently
 *       suppressed by the same {@code expires_at} guard. Both callers receive {@code 202}
 *       on the initial round-trip; future replays with the losing hash will see 409 via
 *       {@code checkOutcome}. This race window is documented in ADR-0002 as the
 *       <i>α decision</i> — a reserve-then-commit shape was considered and deferred.</li>
 * </ul>
 *
 * <h2>SQL invariant</h2>
 * {@link #bind} carries a load-bearing PG-specific clause:
 * {@code ON CONFLICT (idempotency_key) DO UPDATE ... WHERE idempotency_record.expires_at <= EXCLUDED.created_at}.
 * This single statement implements <i>"the first non-expired record wins; expired records
 * are replaced atomically."</i> Moving the clause out of SQL would break race safety, so it
 * is intentionally kept in the JDBC layer rather than reified in Java.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {
    private final IdempotencyRepository repository;
    private final NotificationProperties properties;
    private final Clock clock;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Gate-phase read. Returns:
     * <ul>
     *   <li>{@link IdempotencyResult.HitSameHash} — key bound to the same hash, TTL valid → caller replays.</li>
     *   <li>{@link IdempotencyResult.HitDifferentHash} — key bound to a different hash, TTL valid → caller throws 409.</li>
     *   <li>{@link IdempotencyResult.Miss} — no key, or bound record has expired → caller proceeds with the work.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public IdempotencyResult checkOutcome(IdempotencyKey key, RequestHash hash) {
        Instant now = clock.instant();
        return repository.findById(key)
            .filter(r -> r.getExpiresAt().isAfter(now))
            .<IdempotencyResult>map(r -> r.matches(hash)
                ? new IdempotencyResult.HitSameHash(r.getTargetId())
                : new IdempotencyResult.HitDifferentHash())
            .orElse(new IdempotencyResult.Miss());
    }

    /**
     * Bind-phase atomic write. Run inside the registration transaction
     * ({@link Propagation#MANDATORY}). Suppresses concurrent same-K writes per the
     * race semantics in the class javadoc (first-write-wins via the expires_at guard).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(IdempotencyKey key, RequestHash hash, UUID targetId, Duration ttl) {
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
            ON CONFLICT (idempotency_key) DO UPDATE
            SET request_hash = EXCLUDED.request_hash,
                target_id = EXCLUDED.target_id,
                created_at = EXCLUDED.created_at,
                expires_at = EXCLUDED.expires_at
            WHERE idempotency_record.expires_at <= EXCLUDED.created_at
            """, params);
    }

    /**
     * Unconditional save — currently used only by tests / future tooling that needs to
     * write a fresh record without ON CONFLICT semantics. Production registration must
     * use {@link #bind} for race safety.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void persist(IdempotencyKey key, RequestHash hash, UUID targetId, Duration ttl) {
        IdempotencyRecord r = IdempotencyRecord.create(key, hash, targetId, clock, ttl);
        repository.save(r);
    }
}
