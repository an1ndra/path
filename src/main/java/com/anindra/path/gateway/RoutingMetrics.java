package com.anindra.path.gateway;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exponentially weighted latency and error rate per (provider, public model).
 *
 * <p>Kept in memory on purpose, like the circuit breaker: it is an ordering hint, not a
 * source of truth, and a restart should only cost a cold start. That limitation is
 * documented in docs/LIMITS.md.
 */
@Component
public class RoutingMetrics {

    /** Weight of a new sample. 0.2 gives a ~5-sample half-life: fast to react, not jittery. */
    private static final double ALPHA = 0.2;

    private static final class Sample {
        double latencyMs;
        double errorRate;
        long count;
    }

    private final Map<String, Sample> samples = new ConcurrentHashMap<>();

    private static String key(String providerSlug, String publicModel) {
        return providerSlug + "|" + publicModel;
    }

    public void recordSuccess(String providerSlug, String publicModel, long latencyMs) {
        if (providerSlug == null || publicModel == null) {
            return;
        }
        update(providerSlug, publicModel, Math.max(1, latencyMs), 0.0);
    }

    public void recordFailure(String providerSlug, String publicModel) {
        if (providerSlug == null || publicModel == null) {
            return;
        }
        update(providerSlug, publicModel, 0, 1.0);
    }

    private void update(String providerSlug, String publicModel, long latencyMs, double error) {
        samples.compute(key(providerSlug, publicModel), (k, current) -> {
            Sample next = current == null ? new Sample() : current;
            if (next.count == 0) {
                next.latencyMs = latencyMs;
                next.errorRate = error;
            } else {
                // A failure carries no latency sample; keep the last latency rather than
                // dragging it toward zero.
                if (latencyMs > 0) {
                    next.latencyMs = ALPHA * latencyMs + (1 - ALPHA) * next.latencyMs;
                }
                next.errorRate = ALPHA * error + (1 - ALPHA) * next.errorRate;
            }
            next.count++;
            return next;
        });
    }

    /** @return EWMA latency in ms, or {@link Double#NaN} when there is no sample yet. */
    public double latencyMs(String providerSlug, String publicModel) {
        Sample sample = samples.get(key(providerSlug, publicModel));
        return sample == null || sample.count == 0 ? Double.NaN : sample.latencyMs;
    }

    public double errorRate(String providerSlug, String publicModel) {
        Sample sample = samples.get(key(providerSlug, publicModel));
        return sample == null ? 0.0 : sample.errorRate;
    }

    public long sampleCount(String providerSlug, String publicModel) {
        Sample sample = samples.get(key(providerSlug, publicModel));
        return sample == null ? 0 : sample.count;
    }

    public void reset() {
        samples.clear();
    }

    /** Drops state for providers that have not been used in a while. */
    @Scheduled(fixedDelay = 600_000)
    void evictIdle() {
        // Bounded by distinct (provider, model) pairs, which is small; nothing to do unless
        // the catalogue churns. Kept as a hook so growth is not unbounded by accident.
        if (samples.size() > 10_000) {
            samples.clear();
        }
    }
}
