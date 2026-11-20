package com.anindra.path.seed;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks whether first-boot seeding has finished.
 *
 * Tomcat starts listening during context refresh, before {@code ApplicationRunner}s
 * execute, so without this flag a readiness probe can succeed against a half-seeded
 * database.
 */
@Component
public class SeedState {

    private final AtomicBoolean ready = new AtomicBoolean(false);

    public void markReady() {
        ready.set(true);
    }

    public boolean isReady() {
        return ready.get();
    }
}
