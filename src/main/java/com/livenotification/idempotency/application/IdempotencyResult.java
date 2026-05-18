package com.livenotification.idempotency.application;

import java.util.UUID;

public sealed interface IdempotencyResult
    permits IdempotencyResult.HitSameHash,
            IdempotencyResult.HitDifferentHash,
            IdempotencyResult.Miss {

    record HitSameHash(UUID targetId) implements IdempotencyResult {}
    record HitDifferentHash() implements IdempotencyResult {}
    record Miss() implements IdempotencyResult {}
}
