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
    void aProjectCapBoundsEveryKeyInIt() {
        com.anindra.path.domain.ClientKey a = projectKey(10L, 100L);
        com.anindra.path.domain.ClientKey b = projectKey(11L, 100L);
        com.anindra.path.domain.ClientKey other = projectKey(12L, 200L);

        assertThat(limiter.tryAcquire(a, 5, 1)).isTrue();
        // b is a different key in the same project: the project permit is already held.
        assertThat(limiter.tryAcquire(b, 5, 1)).isFalse();
        // a different project is unaffected.
        assertThat(limiter.tryAcquire(other, 5, 1)).isTrue();

        limiter.release(a);
        assertThat(limiter.tryAcquire(b, 5, 1)).isTrue();
    }

    @Test
    void aFailedProjectPermitReleasesTheKeyPermit() {
        com.anindra.path.domain.ClientKey a = projectKey(20L, 300L);
        com.anindra.path.domain.ClientKey b = projectKey(21L, 300L);
        assertThat(limiter.tryAcquire(a, 5, 1)).isTrue();

        assertThat(limiter.tryAcquire(b, 5, 1)).isFalse();
        // b's key permit was handed back, so it is not counted as in flight.
        assertThat(limiter.inFlight(21L)).isZero();
    }

    @Test
    void aProjectWithoutALimitDoesNotTakePermits() {
        com.anindra.path.domain.ClientKey key = projectKey(30L, 400L);
        assertThat(limiter.tryAcquire(key, 5, null)).isTrue();
        assertThat(limiter.tryAcquire(key, 5, 0)).isTrue();
        assertThat(limiter.inFlight(ConcurrencyLimiter.projectBucket(400L))).isZero();
    }

    private static com.anindra.path.domain.ClientKey projectKey(Long id, Long projectId) {
        com.anindra.path.domain.ClientKey key = new com.anindra.path.domain.ClientKey();
        key.setId(id);
        key.setName("k" + id);
        key.setProjectId(projectId);
        return key;
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
