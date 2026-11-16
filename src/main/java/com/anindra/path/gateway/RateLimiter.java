package com.anindra.path.gateway;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed one-minute window limiter, one bucket per client key. Deliberately in-memory:
 * a single gateway node enforces it exactly; for a cluster, replace with Redis.
 */
@Component
public class RateLimiter {

    private record Window(long minute, int used) {}

    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    /** @return 0 when the call may proceed, otherwise the Retry-After seconds. */
    public int acquire(Long keyId, int rpmLimit) {
        if (rpmLimit <= 0) {
            return 0;
        }
        long minute = Instant.now().getEpochSecond() / 60;
        int[] retryAfter = {0};
        windows.compute(keyId, (id, current) -> {
            if (current == null || current.minute() != minute) {
                return new Window(minute, 1);
            }
            if (current.used() < rpmLimit) {
                return new Window(minute, current.used() + 1);
            }
            retryAfter[0] = (int) Math.max(1, 60 - (Instant.now().getEpochSecond() % 60));
            return current;
        });
        return retryAfter[0];
    }

    @Scheduled(fixedDelay = 300_000)
    void evictStale() {
        long cutoff = Instant.now().getEpochSecond() / 60 - 10;
        windows.entrySet().removeIf(e -> e.getValue().minute() < cutoff);
    }
}
