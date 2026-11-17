package com.anindra.path.gateway;

import com.anindra.path.domain.ClientKey;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.UsageRecord;
import com.anindra.path.repo.UsageRecordRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UsageService {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);

    private final UsageRecordRepository usage;
    private final ApiKeyService apiKeys;
    private final MeterRegistry metrics;

    /** Itemised cost of one call. */
    public record CostBreakdown(BigDecimal inputUsd, BigDecimal cachedInputUsd, BigDecimal cacheWriteUsd,
                                BigDecimal outputUsd, BigDecimal cachedSavingsUsd, BigDecimal totalUsd) {
    }

    /** Everything about the call that is not token accounting. */
    public record CallMeta(long latencyMs, Long ttftMs, boolean streamed, String requestId,
                           String finishReason, int attempts, String fallbackFrom) {
        public static CallMeta nonStream(long latencyMs, String requestId, String finishReason,
                                         int attempts, String fallbackFrom) {
            return new CallMeta(latencyMs, null, false, requestId, finishReason, attempts, fallbackFrom);
        }
    }

    /**
     * Prices a call from the route's per-million rates. Cached and cache-write tokens are
     * charged at their own rate when configured, otherwise at the plain input rate.
     */
    public CostBreakdown cost(ModelRoute route, TokenUsage tokens) {
        BigDecimal inputRate = nz(route.getInputPricePerMillion());
        BigDecimal cachedRate = route.getCachedInputPricePerMillion() != null
                ? route.getCachedInputPricePerMillion() : inputRate;
        BigDecimal writeRate = route.getCacheWritePricePerMillion() != null
                ? route.getCacheWritePricePerMillion() : inputRate;
        BigDecimal outputRate = nz(route.getOutputPricePerMillion());

        BigDecimal inputCost = perMillion(tokens.billableInput(), inputRate);
        BigDecimal cachedCost = perMillion(tokens.cached(), cachedRate);
        BigDecimal writeCost = perMillion(tokens.cacheWrite(), writeRate);
        BigDecimal outputCost = perMillion(tokens.completion(), outputRate);

        BigDecimal savings = perMillion(tokens.cached(), inputRate).subtract(cachedCost);
        if (savings.signum() < 0) {
            savings = ZERO;
        }

        BigDecimal total = inputCost.add(cachedCost).add(writeCost).add(outputCost)
                .setScale(8, RoundingMode.HALF_UP);
        return new CostBreakdown(inputCost, cachedCost, writeCost, outputCost, savings, total);
    }

    private static BigDecimal perMillion(int tokens, BigDecimal ratePerMillion) {
        if (tokens <= 0 || ratePerMillion == null || ratePerMillion.signum() == 0) {
            return ZERO;
        }
        return BigDecimal.valueOf(tokens)
                .multiply(ratePerMillion)
                .divide(MILLION, 12, RoundingMode.HALF_UP)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Transactional
    public BigDecimal recordSuccess(ClientKey key, ChatRequestSpec spec, RouteResolver.ResolvedRoute resolved,
                                    TokenUsage tokens, CallMeta meta) {
        TokenUsage u = tokens == null ? TokenUsage.EMPTY : tokens;
        CostBreakdown cost = cost(resolved.route(), u);

        UsageRecord record = new UsageRecord();
        record.setClientKeyId(key.getId());
        record.setClientKeyName(key.getName());
        record.setPublicModelId(spec.publicModel());
        record.setProviderSlug(resolved.providerSlug());
        record.setUpstreamModelId(resolved.route().getUpstreamModelId());
        record.setPromptTokens(u.prompt());
        record.setCompletionTokens(u.completion());
        record.setTotalTokens(u.total());
        record.setCachedInputTokens(u.cached());
        record.setCacheWriteTokens(u.cacheWrite());
        record.setReasoningTokens(u.reasoning());
        record.setAudioInputTokens(u.audioInput());
        record.setAudioOutputTokens(u.audioOutput());
        record.setUsageEstimated(u.estimated());
        record.setInputCostUsd(cost.inputUsd());
        record.setCachedInputCostUsd(cost.cachedInputUsd());
        record.setCacheWriteCostUsd(cost.cacheWriteUsd());
        record.setOutputCostUsd(cost.outputUsd());
        record.setCachedSavingsUsd(cost.cachedSavingsUsd());
        record.setCostUsd(cost.totalUsd());
        record.setLatencyMs(meta.latencyMs());
        record.setTtftMs(meta.ttftMs());
        record.setTokensPerSecond(throughput(u, meta));
        record.setStreamed(meta.streamed());
        record.setUpstreamRequestId(meta.requestId());
        record.setFinishReason(meta.finishReason());
        record.setAttempts(meta.attempts());
        record.setFallbackFrom(meta.fallbackFrom());
        record.setStatus(UsageRecord.Status.SUCCESS);
        usage.save(record);
        recordMetrics(record);

        apiKeys.charge(key.getId(), cost.totalUsd());
        return cost.totalUsd();
    }

    /** Records a cache hit: no upstream call, no charge, savings carried for reporting. */
    @Transactional
    public void recordCacheHit(ClientKey key, ChatRequestSpec spec, RouteResolver.ResolvedRoute resolved,
                               ResponseCacheService.Entry entry, boolean streamed) {
        UsageRecord record = new UsageRecord();
        record.setClientKeyId(key.getId());
        record.setClientKeyName(key.getName());
        record.setPublicModelId(spec.publicModel());
        record.setProviderSlug(entry.providerSlug());
        record.setUpstreamModelId(resolved.route().getUpstreamModelId());
        record.setPromptTokens(entry.promptTokens());
        record.setCompletionTokens(entry.completionTokens());
        record.setTotalTokens(entry.totalTokens());
        record.setCostUsd(BigDecimal.ZERO);
        record.setCachedSavingsUsd(entry.costUsd() == null ? BigDecimal.ZERO : entry.costUsd());
        record.setLatencyMs(0);
        record.setStreamed(streamed);
        record.setAttempts(0);
        record.setFinishReason("cache");
        record.setStatus(UsageRecord.Status.SUCCESS);
        usage.save(record);
        metrics.counter("path.cache.hits",
                "model", String.valueOf(record.getPublicModelId())).increment();
        recordMetrics(record);
    }

    @Transactional
    public void recordError(ClientKey key, ChatRequestSpec spec, String providerSlug, String upstreamModel,
                            long latencyMs, boolean streamed, int attempts, String code, String message) {
        UsageRecord record = new UsageRecord();
        if (key != null) {
            record.setClientKeyId(key.getId());
            record.setClientKeyName(key.getName());
        }
        record.setPublicModelId(spec == null ? null : spec.publicModel());
        record.setProviderSlug(providerSlug);
        record.setUpstreamModelId(upstreamModel);
        record.setLatencyMs(latencyMs);
        record.setStreamed(streamed);
        record.setAttempts(attempts);
        record.setStatus(UsageRecord.Status.ERROR);
        record.setErrorCode(code);
        record.setErrorMessage(message != null && message.length() > 500 ? message.substring(0, 500) : message);
        usage.save(record);
        recordMetrics(record);
    }

    /** Feeds the Prometheus endpoint. Tag values stay low-cardinality (model, provider, status). */
    private void recordMetrics(UsageRecord record) {
        String model = String.valueOf(record.getPublicModelId());
        String provider = String.valueOf(record.getProviderSlug());
        metrics.counter("path.requests",
                "model", model,
                "provider", provider,
                "status", record.getStatus().name().toLowerCase(),
                "streamed", String.valueOf(record.isStreamed())).increment();
        metrics.timer("path.latency", "provider", provider)
                .record(java.time.Duration.ofMillis(record.getLatencyMs()));
        metrics.counter("path.tokens", "type", "prompt").increment(record.getPromptTokens());
        metrics.counter("path.tokens", "type", "completion").increment(record.getCompletionTokens());
        metrics.counter("path.tokens", "type", "cached").increment(record.getCachedInputTokens());
        if (record.getCostUsd() != null) {
            metrics.counter("path.cost.usd",
                    "model", model, "provider", provider).increment(record.getCostUsd().doubleValue());
        }
    }

    private static BigDecimal throughput(TokenUsage usage, CallMeta meta) {
        if (usage.completion() <= 0) {
            return null;
        }
        long window = meta.latencyMs();
        if (meta.ttftMs() != null) {
            window = Math.max(1, meta.latencyMs() - meta.ttftMs());
        }
        if (window <= 0) {
            return null;
        }
        return BigDecimal.valueOf(usage.completion())
                .multiply(BigDecimal.valueOf(1000))
                .divide(BigDecimal.valueOf(window), 2, RoundingMode.HALF_UP);
    }

    /** Spend since a timestamp, used by the daily/monthly windows. */
    public BigDecimal spendFor(Long keyId, Instant from) {
        return nz(usage.sumCostForKeySince(keyId, from));
    }

    /** Spend across every key in a project since a timestamp. */
    public BigDecimal spendForProject(Long projectId, Instant from) {
        return nz(usage.sumCostForProjectSince(projectId, from));
    }

    public Map<String, Object> keySummary(ClientKey key, Duration window) {
        Instant from = Instant.now().minus(window);
        long requests = usage.countByClientKeyIdAndCreatedAtAfter(key.getId(), from);
        BigDecimal spend = usage.sumCostForKeySince(key.getId(), from);
        long promptTokens = nz(usage.sumPromptTokensForKeySince(key.getId(), from));
        long completionTokens = nz(usage.sumCompletionTokensForKeySince(key.getId(), from));
        long cachedTokens = nz(usage.sumCachedTokensForKeySince(key.getId(), from));
        long reasoningTokens = nz(usage.sumReasoningTokensForKeySince(key.getId(), from));
        BigDecimal savings = nz(usage.sumCachedSavingsForKeySince(key.getId(), from));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "usage");
        out.put("window", window.toString());
        out.put("key_id", key.getId());
        out.put("key_name", key.getName());
        out.put("requests", requests);
        out.put("prompt_tokens", promptTokens);
        out.put("completion_tokens", completionTokens);
        out.put("total_tokens", promptTokens + completionTokens);
        out.put("cached_input_tokens", cachedTokens);
        out.put("cache_hit_ratio", ratio(cachedTokens, promptTokens));
        out.put("cached_savings_usd", savings);
        out.put("reasoning_tokens", reasoningTokens);
        out.put("cost_usd", spend);
        out.put("cost_breakdown", Map.of(
                "input", nz(usage.sumInputCostForKeySince(key.getId(), from)),
                "cached_input", nz(usage.sumCachedCostForKeySince(key.getId(), from)),
                "cache_write", nz(usage.sumCacheWriteCostForKeySince(key.getId(), from)),
                "output", nz(usage.sumOutputCostForKeySince(key.getId(), from))));
        out.put("credit_limit_usd", key.getCreditLimitUsd());
        out.put("used_usd", key.getUsedUsd());
        out.put("remaining_usd", apiKeys.remaining(key));
        out.put("recent", recentRecords(key));
        return out;
    }

    private static double ratio(long part, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private List<Map<String, Object>> recentRecords(ClientKey key) {
        List<Map<String, Object>> recent = new ArrayList<>();
        usage.findByClientKeyIdOrderByCreatedAtDesc(key.getId(), PageRequest.of(0, 20)).forEach(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("at", r.getCreatedAt().toString());
            row.put("model", r.getPublicModelId());
            row.put("provider", r.getProviderSlug());
            row.put("prompt_tokens", r.getPromptTokens());
            row.put("completion_tokens", r.getCompletionTokens());
            row.put("cached_input_tokens", r.getCachedInputTokens());
            row.put("cache_write_tokens", r.getCacheWriteTokens());
            row.put("reasoning_tokens", r.getReasoningTokens());
            row.put("cost_usd", r.getCostUsd());
            row.put("cached_savings_usd", r.getCachedSavingsUsd());
            row.put("latency_ms", r.getLatencyMs());
            if (r.getTtftMs() != null) {
                row.put("ttft_ms", r.getTtftMs());
            }
            if (r.getTokensPerSecond() != null) {
                row.put("tokens_per_second", r.getTokensPerSecond());
            }
            if (r.getUpstreamRequestId() != null) {
                row.put("upstream_request_id", r.getUpstreamRequestId());
            }
            if (r.getFinishReason() != null) {
                row.put("finish_reason", r.getFinishReason());
            }
            row.put("attempts", r.getAttempts());
            if (r.getFallbackFrom() != null) {
                row.put("fallback_from", r.getFallbackFrom());
            }
            row.put("usage_estimated", r.isUsageEstimated());
            row.put("streamed", r.isStreamed());
            row.put("status", r.getStatus().name().toLowerCase());
            if (r.getErrorCode() != null) {
                row.put("error", r.getErrorCode());
            }
            recent.add(row);
        });
        return recent;
    }

    public Map<String, Object> adminStats(Duration window) {
        Instant from = Instant.now().minus(window);
        long requests = usage.countByCreatedAtAfter(from);
        BigDecimal spend = usage.sumCostSince(from);
        long promptTokens = nz(usage.sumPromptTokensSince(from));
        long cachedTokens = nz(usage.sumCachedTokensSince(from));
        long totalTokens = nz(usage.sumTokensSince(from));
        BigDecimal savings = nz(usage.sumCachedSavingsSince(from));

        List<Long> latencies = new ArrayList<>(usage.findLatenciesSince(from));
        latencies.sort(Comparator.naturalOrder());

        List<Long> ttfts = new ArrayList<>(usage.findTtftSince(from));
        ttfts.sort(Comparator.naturalOrder());

        List<BigDecimal> throughputs = new ArrayList<>(usage.findThroughputSince(from));
        throughputs.sort(Comparator.naturalOrder());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", window.toString());
        out.put("requests", requests);
        out.put("total_tokens", totalTokens);
        out.put("cost_usd", spend);
        out.put("latency_ms", Map.of(
                "p50", percentile(latencies, 0.50),
                "p95", percentile(latencies, 0.95),
                "p99", percentile(latencies, 0.99)));
        out.put("ttft_ms", Map.of(
                "p50", percentile(ttfts, 0.50),
                "p95", percentile(ttfts, 0.95)));
        out.put("tokens_per_second", Map.of("p50", percentileOfDecimals(throughputs, 0.50)));

        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("prompt_tokens", promptTokens);
        cache.put("cached_input_tokens", cachedTokens);
        cache.put("hit_ratio", ratio(cachedTokens, promptTokens));
        cache.put("savings_usd", savings);
        out.put("cache", cache);

        out.put("fallback_requests", usage.countByAttemptsGreaterThanAndCreatedAtAfter(1, from));
        out.put("estimated_usage_requests", usage.countByUsageEstimatedTrueAndCreatedAtAfter(from));

        Map<String, Object> byProvider = new LinkedHashMap<>();
        for (Object[] row : usage.groupByProviderSince(from)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("requests", row[1]);
            entry.put("cost_usd", row[2]);
            byProvider.put(String.valueOf(row[0]), entry);
        }
        out.put("by_provider", byProvider);

        List<Map<String, Object>> byModel = new ArrayList<>();
        for (Object[] row : usage.groupByModelSince(from)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("model", row[0]);
            entry.put("requests", row[1]);
            entry.put("tokens", row[2]);
            entry.put("cost_usd", row[3]);
            byModel.add(entry);
        }
        out.put("by_model", byModel);
        return out;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static double percentileOfDecimals(List<BigDecimal> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))).doubleValue();
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
