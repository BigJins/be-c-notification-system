package com.livenotification.idempotency.application;

import com.livenotification.idempotency.domain.IdempotencyKey;
import com.livenotification.idempotency.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    default java.util.Optional<IdempotencyRecord> findById(IdempotencyKey key) {
        return findById(key.value());
    }

    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
