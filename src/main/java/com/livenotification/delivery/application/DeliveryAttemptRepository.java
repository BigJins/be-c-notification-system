package com.livenotification.delivery.application;

import com.livenotification.delivery.domain.DeliveryAttempt;
import com.livenotification.delivery.domain.DeliveryAttemptId;
import com.livenotification.delivery.domain.DeliveryAttemptState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    @Query(value = """
        SELECT id FROM delivery_attempt
        WHERE state = 'READY' AND next_attempt_at <= now()
        ORDER BY next_attempt_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<UUID> findClaimableIds(@Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
        UPDATE delivery_attempt
        SET state = 'IN_PROGRESS',
            claimed_by = :workerId,
            claimed_until = :until,
            updated_at = :now
        WHERE id = ANY(:ids) AND state = 'READY'
        """, nativeQuery = true)
    int claimByIds(@Param("ids") UUID[] ids,
                   @Param("workerId") String workerId,
                   @Param("until") Instant until,
                   @Param("now") Instant now);

    long countByState(DeliveryAttemptState state);

    @Query(value = """
        SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(next_attempt_at)))::bigint, 0)
        FROM delivery_attempt WHERE state = 'READY'
        """, nativeQuery = true)
    long computeLagSeconds();

    @Query(value = """
        SELECT COUNT(*) FROM delivery_attempt
        WHERE state = 'IN_PROGRESS' AND claimed_until < now()
        """, nativeQuery = true)
    long countStuckAttempts();

    @Query(value = """
        SELECT * FROM delivery_attempt
        WHERE state = 'IN_PROGRESS' AND claimed_until < :now
        """, nativeQuery = true)
    List<DeliveryAttempt> findStuckAttempts(@Param("now") Instant now);

    @Modifying
    @Query(value = """
        UPDATE delivery_attempt
        SET state = 'READY',
            claimed_by = NULL,
            claimed_until = NULL,
            updated_at = :now
        WHERE state = 'IN_PROGRESS' AND claimed_until < :now
        """, nativeQuery = true)
    int releaseStuck(@Param("now") Instant now);

    @Modifying
    @Query("""
        DELETE FROM DeliveryAttempt a
        WHERE a.state IN :states AND a.updatedAt < :cutoff
        """)
    int deleteByStateInAndUpdatedAtBefore(
        @Param("states") java.util.Collection<DeliveryAttemptState> states,
        @Param("cutoff") java.time.Instant cutoff);
}
