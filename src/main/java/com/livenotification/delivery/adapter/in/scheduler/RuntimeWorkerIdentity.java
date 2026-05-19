package com.livenotification.delivery.adapter.in.scheduler;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Production {@link WorkerIdentity}. Resolves {@code <host>-<pid>} once at construction
 * and returns the cached value forever after. Format is deterministic regardless of
 * which host-resolution source actually wins, so callers (and DB queries / dashboards
 * grepping {@code claimed_by}) see a single predictable shape.
 *
 * <p><b>Prefix guarantee.</b> The value <i>always</i> starts with the resolved host
 * component. On Kubernetes, {@code HOSTNAME=<pod-name>} is injected automatically, so
 * the value reads {@code <pod-name>-<pid>} — operators can grep dashboards by pod
 * name prefix to find which instance owns a stuck attempt.</p>
 *
 * <p>Host resolution order:</p>
 * <ol>
 *   <li>{@code HOSTNAME} environment variable (set by Kubernetes / container runtimes).</li>
 *   <li>{@link InetAddress#getLocalHost()} hostname (works on bare-metal / VM dev boxes).</li>
 *   <li>Literal {@code "host"} (DNS failure fallback — extremely rare).</li>
 * </ol>
 *
 * <p>The final value is truncated to 64 chars to fit
 * {@code delivery_attempt.claimed_by VARCHAR(64)}. DNS-1123 pod names are limited to
 * 63 chars, so the host prefix is never clipped on Kubernetes — only the trailing
 * {@code -<pid>} suffix may be partially truncated for pathologically long hostnames.</p>
 */
@Component
public class RuntimeWorkerIdentity implements WorkerIdentity {

    private static final int CLAIMED_BY_MAX = 64;

    private final String value;

    public RuntimeWorkerIdentity() {
        String raw = resolveHost() + "-" + ProcessHandle.current().pid();
        this.value = raw.length() <= CLAIMED_BY_MAX ? raw : raw.substring(0, CLAIMED_BY_MAX);
    }

    @Override
    public String value() {
        return value;
    }

    private static String resolveHost() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) return env;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "host";
        }
    }
}
