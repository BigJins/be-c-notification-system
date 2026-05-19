package com.livenotification.notification.application;

/**
 * Result of {@link NotificationService#register} as a single typed outcome rather than
 * a pair of boolean flags. Each variant maps to one canonical HTTP response
 * (status code + headers); see {@code NotificationController#register}.
 *
 * <p>The {@code Idempotency-Key} replay path collapses to {@link IdempotencyReplay} —
 * {@code X-Event-Duplicate} is intentionally not set even when the replayed key happens
 * to point at a notification whose event would also dedup. The previous two-flag encoding
 * conflated these signals; see ADR-0002 for the decision and {@code docs/document.md} §4
 * for the underlying response-header table.</p>
 */
public sealed interface RegisterOutcome
    permits RegisterOutcome.NewlyCreated,
            RegisterOutcome.EventDuplicate,
            RegisterOutcome.IdempotentReplay {

    NotificationDetail detail();

    /** Brand-new notification — 202, both headers false. */
    record NewlyCreated(NotificationDetail detail) implements RegisterOutcome {}

    /** Event-level dedup hit (no idempotency replay) — 200, X-Event-Duplicate=true. */
    record EventDuplicate(NotificationDetail detail) implements RegisterOutcome {}

    /** Idempotency-Key replay (same body) — 200, X-Idempotent-Replay=true; event-dup signal suppressed. */
    record IdempotentReplay(NotificationDetail detail) implements RegisterOutcome {}
}
