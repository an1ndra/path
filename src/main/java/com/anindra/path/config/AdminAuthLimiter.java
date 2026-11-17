package com.anindra.path.config;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down guessing of the admin token: too many failed attempts from one address
 * within the window return 429 instead of another cheap comparison.
 *
 * A per-address cap is only as good as the address. Behind Cloudflare the peer address is
 * an edge node that changes per request, and any client-supplied forwarding header can be
 * rotated at will, so the per-address counter alone allowed thousands of guesses. The
 * process-wide counter closes that: once {@code global-max-failures} failures land inside
 * the window, every admin authentication is refused until the window rolls over, whatever
 * address it claims to come from.
 */
@Component
@RequiredArgsConstructor
public class AdminAuthLimiter {

    private record Entry(long windowStart, int failures) {
    }

    private final GatewayProperties props;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Process-wide window, guarded because two counters must roll over together. */
    private final Object globalLock = new Object();
    private long globalWindowStart = System.currentTimeMillis();
    private int globalFailures;

    public long retryAfterSeconds(String address) {
        long now = System.currentTimeMillis();
        long windowMs = props.getAdminAuth().getWindow().toMillis();

        synchronized (globalLock) {
            rollGlobalWindow(now, windowMs);
            int limit = props.getAdminAuth().getGlobalMaxFailures();
            if (limit > 0 && globalFailures >= limit) {
                return remainingSeconds(now, globalWindowStart, windowMs);
            }
        }

        Entry entry = entries.get(address);
        if (entry == null) {
            return 0;
        }
        if (now - entry.windowStart() >= windowMs
                || entry.failures() < props.getAdminAuth().getMaxFailures()) {
            return 0;
        }
        return remainingSeconds(now, entry.windowStart(), windowMs);
    }

    public void onFailure(String address) {
        long now = System.currentTimeMillis();
        long windowMs = props.getAdminAuth().getWindow().toMillis();

        synchronized (globalLock) {
            rollGlobalWindow(now, windowMs);
            globalFailures++;
        }

        entries.compute(address, (key, current) -> {
            if (current == null || now - current.windowStart() >= windowMs) {
                return new Entry(now, 1);
            }
            return new Entry(current.windowStart(), current.failures() + 1);
        });
    }

    public void onSuccess(String address) {
        entries.remove(address);
    }

    /** Test seam: the global counter has no public reset in production. */
    void resetGlobalWindow() {
        synchronized (globalLock) {
            globalWindowStart = System.currentTimeMillis();
            globalFailures = 0;
        }
    }

    private void rollGlobalWindow(long now, long windowMs) {
        if (now - globalWindowStart >= windowMs) {
            globalWindowStart = now;
            globalFailures = 0;
        }
    }

    private static long remainingSeconds(long now, long windowStart, long windowMs) {
        return Math.max(1, (windowMs - (now - windowStart)) / 1000);
    }

    @Scheduled(fixedDelay = 300_000)
    void evictStale() {
        long cutoff = System.currentTimeMillis() - props.getAdminAuth().getWindow().toMillis();
        entries.entrySet().removeIf(e -> e.getValue().windowStart() < cutoff);
    }
}
