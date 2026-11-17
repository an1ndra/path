package com.anindra.path.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyLimiterTest {

    private final ConcurrencyLimiter limiter = new ConcurrencyLimiter();

    @Test
    void capsConcurrentPermitsPerKey() {
        assertThat(limiter.tryAcquire(1L, 2)).isTrue();
        assertThat(limiter.tryAcquire(1L, 2)).isTrue();
        assertThat(limiter.tryAcquire(1L, 2)).isFalse();
        assertThat(limiter.inFlight(1L)).isEqualTo(2);

        limiter.release(1L);
        assertThat(limiter.inFlight(1L)).isEqualTo(1);
        assertThat(limiter.tryAcquire(1L, 2)).isTrue();
    }

    @Test
    void keysAreIndependent() {
        assertThat(limiter.tryAcquire(1L, 1)).isTrue();
        assertThat(limiter.tryAcquire(1L, 1)).isFalse();
        assertThat(limiter.tryAcquire(2L, 1)).isTrue();
    }

    @Test
    void zeroMeansUnlimited() {
        assertThat(limiter.tryAcquire(3L, 0)).isTrue();
        assertThat(limiter.tryAcquire(3L, 0)).isTrue();
        assertThat(limiter.inFlight(3L)).isZero();
    }

    @Test
    void releaseDoesNotOverRelease() {
        assertThat(limiter.tryAcquire(4L, 1)).isTrue();
        limiter.release(4L);
        limiter.release(4L);
        assertThat(limiter.inFlight(4L)).isZero();
        assertThat(limiter.tryAcquire(4L, 1)).isTrue();
        assertThat(limiter.tryAcquire(4L, 1)).isFalse();
    }
}
