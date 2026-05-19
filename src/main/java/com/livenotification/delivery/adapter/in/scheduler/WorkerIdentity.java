package com.livenotification.delivery.adapter.in.scheduler;

/**
 * Source of the worker identifier written into {@code delivery_attempt.claimed_by}.
 * Extracted as a seam so production and tests share one contract — the test wires a
 * fixed value; production wires {@link RuntimeWorkerIdentity}, which resolves a
 * deterministic {@code <host>-<pid>} string once at startup. Decoupling the rule
 * from {@link DispatchWorker} prevents environment variables (e.g. {@code HOSTNAME})
 * from silently changing the format and breaking tests.
 *
 * <p>Implementations must return a value ≤ 64 chars (the DB column width).</p>
 */
@FunctionalInterface
public interface WorkerIdentity {
    String value();
}
