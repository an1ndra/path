package com.anindra.path.config;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window failure counter, shared by the admin-token and client-key auth gates.
 *
 * Two counters: per address, and (optionally) process-wide. The per-address one is the real
 * control — it limits how many credentials a single source can have evaluated. The global
 * one is off unless configured, because a fail-closed global cap is a denial-of-service
 * against the operator: one hostile source spending the budget locks everyone out.
 */
class FailureWindow {

    private record Entry(long windowStart, int failures) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Object globalLock = new Object();
    private long globalWindowStart = System.currentTimeMillis();
    private int globalFailures;

    private final int maxFailures;
    private final Duration window;
    private final int globalMaxFailures;

    FailureWindow(int maxFailures, Duration window, int globalMaxFailures) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.globalMaxFailures = globalMaxFailures;
    }

    long retryAfterSeconds(String address) {
        long now = System.currentTimeMillis();
        long windowMs = window.toMillis();

        synchronized (globalLock) {
            rollGlobalWindow(now, windowMs);
            if (globalMaxFailures > 0 && globalFailures >= globalMaxFailures) {
                return remainingSeconds(now, globalWindowStart, windowMs);
            }
        }

        Entry entry = entries.get(address);
        if (entry == null) {
            return 0;
        }
        if (now - entry.windowStart() >= windowMs || entry.failures() < maxFailures) {
            return 0;
        }
        return remainingSeconds(now, entry.windowStart(), windowMs);
    }

    void onFailure(String address) {
        long now = System.currentTimeMillis();
        long windowMs = window.toMillis();

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

    void onSuccess(String address) {
        entries.remove(address);
    }

    void evictStale() {
        long cutoff = System.currentTimeMillis() - window.toMillis();
        entries.entrySet().removeIf(e -> e.getValue().windowStart() < cutoff);
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
}
