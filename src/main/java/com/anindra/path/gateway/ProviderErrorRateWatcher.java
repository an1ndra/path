package com.anindra.path.gateway;

import com.anindra.path.admin.AlertService;
import com.anindra.path.config.GatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches the routing EWMA and reports a provider whose error rate stays high while its
 * circuit is closed.
 *
 * <p>The breaker only opens after consecutive failures, so a provider failing 30% of the
 * time with an occasional success never trips it: every affected request is quietly retried
 * onto a fallback, and cost and latency rise with nobody watching. This check runs once a
 * minute and reuses the EWMA the route view already shows.
 *
 * <p>One alert per provider per hour, and one recovery per incident. The threshold is
 * {@code gateway.alert.error-rate} (default 0.25) and the rate is ignored until the
 * provider has {@code gateway.alert.min-samples} recent calls (default 20).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProviderErrorRateWatcher {

    private final RoutingMetrics routing;
    private final AlertService alerts;
    private final GatewayProperties props;

    /** Providers currently in the "high" state, so recovery fires once. */
    private final java.util.Set<String> high = ConcurrentHashMap.newKeySet();
    /** Provider -> the hour its last high alert was sent for. */
    private final Map<String, Long> alertedHour = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 60_000)
    public void check() {
        double threshold = props.getAlert().getErrorRate();
        if (threshold <= 0) {
            return;
        }
        long minSamples = Math.max(1, props.getAlert().getMinSamples());

        Map<String, Aggregate> byProvider = aggregate(routing.snapshot());
        long hour = Instant.now().getEpochSecond() / 3_600;

        byProvider.forEach((provider, aggregate) -> {
            if (aggregate.samples < minSamples) {
                // A cold start is not an incident.
                return;
            }
            double rate = aggregate.errorRate();
            if (rate > threshold) {
                high.add(provider);
                Long lastHour = alertedHour.get(provider);
                if (lastHour == null || lastHour != hour) {
                    alertedHour.put(provider, hour);
                    alerts.notifyOnce("provider.error_rate_high:" + provider + ":" + hour,
                            "provider.error_rate_high", payload(provider, aggregate, threshold));
                }
            } else if (rate < threshold / 2 && high.remove(provider)) {
                alertedHour.remove(provider);
                alerts.notifyOnce("provider.error_rate_normal:" + provider + ":" + hour,
                        "provider.error_rate_normal", payload(provider, aggregate, threshold));
            }
        });
    }

    private static Map<String, Object> payload(String provider, Aggregate aggregate, double threshold) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", provider);
        body.put("error_rate", round(aggregate.errorRate()));
        body.put("samples", aggregate.samples);
        body.put("threshold", threshold);
        if (!Double.isNaN(aggregate.latencyMs())) {
            body.put("latency_ms", Math.round(aggregate.latencyMs()));
        }
        return body;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    /** Count-weighted mean across the provider's models. */
    static Map<String, Aggregate> aggregate(List<RoutingMetrics.Snapshot> snapshot) {
        Map<String, Aggregate> out = new LinkedHashMap<>();
        for (RoutingMetrics.Snapshot row : snapshot) {
            if (row.providerSlug() == null || row.count() <= 0) {
                continue;
            }
            Aggregate aggregate = out.computeIfAbsent(row.providerSlug(), k -> new Aggregate());
            aggregate.errorSum += row.errorRate() * row.count();
            if (!Double.isNaN(row.latencyMs()) && row.latencyMs() > 0) {
                aggregate.latencySum += row.latencyMs() * row.count();
                aggregate.latencyWeight += row.count();
            }
            aggregate.samples += row.count();
        }
        return out;
    }

    static final class Aggregate {
        double errorSum;
        double latencySum;
        double latencyWeight;
        long samples;

        double errorRate() {
            return samples == 0 ? 0 : errorSum / samples;
        }

        double latencyMs() {
            return latencyWeight == 0 ? Double.NaN : latencySum / latencyWeight;
        }
    }
}
