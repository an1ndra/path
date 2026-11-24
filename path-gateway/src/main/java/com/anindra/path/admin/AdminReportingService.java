package com.anindra.path.admin;

import com.anindra.path.api.Durations;
import com.anindra.path.domain.UsageRecord;
import com.anindra.path.repo.UsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads usage records for the admin dashboard. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminReportingService {

    private final UsageRecordRepository usage;
    private final AdminService adminService;
    private final com.anindra.path.repo.ClientKeyRepository keys;

    public Map<String, Object> usage(Long keyId, Long projectId, String endUser, String window,
                                     int limit) {
        Duration parsed = Durations.cap(Durations.parse(window, Duration.ofHours(24)), Duration.ofDays(365));
        int capped = Math.max(1, Math.min(limit, 500));
        java.time.Instant from = java.time.Instant.now().minus(parsed);

        List<UsageRecord> records;
        if (endUser != null && !endUser.isBlank()) {
            records = keyId != null
                    ? usage.findByClientKeyIdAndEndUserOrderByCreatedAtDesc(
                            keyId, endUser, PageRequest.of(0, capped))
                    : usage.findAll(PageRequest.of(0, capped)).stream()
                            .filter(r -> endUser.equals(r.getEndUser())).toList();
        } else if (keyId != null) {
            records = usage.findByClientKeyIdOrderByCreatedAtDesc(keyId, PageRequest.of(0, capped));
        } else if (projectId != null) {
            List<Long> keyIds = projectKeyIds(projectId);
            records = keyIds.isEmpty() ? List.of()
                    : usage.findByClientKeyIdInAndCreatedAtAfterOrderByCreatedAtDesc(
                            keyIds, from, PageRequest.of(0, capped));
        } else {
            records = usage.findByCreatedAtAfterOrderByCreatedAtDesc(from, PageRequest.of(0, capped));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (UsageRecord r : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("at", r.getCreatedAt().toString());
            row.put("key_id", r.getClientKeyId());
            row.put("key_name", r.getClientKeyName());
        row.put("end_user", r.getEndUser());
            row.put("model", r.getPublicModelId());
            row.put("provider", r.getProviderSlug());
            row.put("upstream_model", r.getUpstreamModelId());
            row.put("prompt_tokens", r.getPromptTokens());
            row.put("completion_tokens", r.getCompletionTokens());
            row.put("total_tokens", r.getTotalTokens());
            row.put("cached_input_tokens", r.getCachedInputTokens());
            row.put("cache_write_tokens", r.getCacheWriteTokens());
            row.put("cache_tier", r.getCacheTier());
            row.put("reasoning_tokens", r.getReasoningTokens());
            row.put("cost_usd", r.getCostUsd());
            row.put("cost_breakdown", Map.of(
                    "input", r.getInputCostUsd(),
                    "cached_input", r.getCachedInputCostUsd(),
                    "cache_write", r.getCacheWriteCostUsd(),
                    "output", r.getOutputCostUsd()));
            row.put("cached_savings_usd", r.getCachedSavingsUsd());
            row.put("latency_ms", r.getLatencyMs());
            row.put("ttft_ms", r.getTtftMs());
            row.put("tokens_per_second", r.getTokensPerSecond());
            row.put("upstream_request_id", r.getUpstreamRequestId());
            row.put("request_id", r.getRequestId());
            row.put("finish_reason", r.getFinishReason());
            row.put("attempts", r.getAttempts());
            row.put("fallback_from", r.getFallbackFrom());
            row.put("usage_estimated", r.isUsageEstimated());
            row.put("streamed", r.isStreamed());
            row.put("status", r.getStatus().name().toLowerCase());
            if (r.getErrorCode() != null) {
                row.put("error_code", r.getErrorCode());
                row.put("error_message", r.getErrorMessage());
            }
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "list");
        out.put("window", parsed.toString());
        out.put("project_id", projectId);
        out.put("end_user", endUser);
        out.put("count", rows.size());
        out.put("data", rows);
        return out;
    }

    /** Comma-separated statement of calls, one row per request, oldest first. */
    public String usageCsv(Long keyId, Long projectId, String endUser, java.time.Instant from,
                           java.time.Instant to, int limit) {
        int capped = Math.max(1, Math.min(limit, 50_000));
        List<UsageRecord> rows;
        if (projectId != null) {
            List<Long> keyIds = projectKeyIds(projectId);
            rows = keyIds.isEmpty() ? List.of()
                    : usage.findByClientKeyIdInAndCreatedAtBetweenOrderByCreatedAtAsc(
                            keyIds, from, to, PageRequest.of(0, capped));
        } else if (endUser != null && !endUser.isBlank()) {
            rows = usage.findByCreatedAtBetweenOrderByCreatedAtAsc(from, to, PageRequest.of(0, capped))
                    .stream().filter(r -> endUser.equals(r.getEndUser())).toList();
        } else {
            rows = usage.findByCreatedAtBetweenOrderByCreatedAtAsc(from, to, PageRequest.of(0, capped));
        }

        StringBuilder csv = new StringBuilder();
        csv.append("created_at,key_id,key_name,model,provider,upstream_model,prompt_tokens,")
                .append("completion_tokens,cached_input_tokens,cache_write_tokens,reasoning_tokens,")
                .append("input_cost_usd,cached_input_cost_usd,cache_write_cost_usd,output_cost_usd,")
                .append("cost_usd,latency_ms,ttft_ms,streamed,status,error_code,end_user\n");

        for (UsageRecord r : rows) {
            if (keyId != null && !keyId.equals(r.getClientKeyId())) {
                continue;
            }
            csv.append(r.getCreatedAt()).append(',')
                    .append(r.getClientKeyId() == null ? "" : r.getClientKeyId()).append(',')
                    .append(escape(r.getClientKeyName())).append(',')
                    .append(escape(r.getPublicModelId())).append(',')
                    .append(escape(r.getProviderSlug())).append(',')
                    .append(escape(r.getUpstreamModelId())).append(',')
                    .append(r.getPromptTokens()).append(',')
                    .append(r.getCompletionTokens()).append(',')
                    .append(r.getCachedInputTokens()).append(',')
                    .append(r.getCacheWriteTokens()).append(',')
                    .append(r.getReasoningTokens()).append(',')
                    .append(r.getInputCostUsd()).append(',')
                    .append(r.getCachedInputCostUsd()).append(',')
                    .append(r.getCacheWriteCostUsd()).append(',')
                    .append(r.getOutputCostUsd()).append(',')
                    .append(r.getCostUsd()).append(',')
                    .append(r.getLatencyMs()).append(',')
                    .append(r.getTtftMs() == null ? "" : r.getTtftMs()).append(',')
                    .append(r.isStreamed()).append(',')
                    .append(r.getStatus().name().toLowerCase()).append(',')
                    .append(escape(r.getErrorCode())).append(',')
                    .append(escape(r.getEndUser()))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public Map<String, Object> stats(String window, Long projectId) {
        return stats(window, projectId, null);
    }

    /**
     * Aggregate stats, plus an optional bucketed series when {@code bucket} is given.
     * Buckets are filled with zeroes so a chart never has holes, and the window is clamped
     * to at most {@link #MAX_POINTS} buckets.
     */
    public Map<String, Object> stats(String window, Long projectId, String bucket) {
        Duration parsed = Durations.cap(Durations.parse(window, Duration.ofHours(24)), Duration.ofDays(365));
        if (bucket != null && !bucket.isBlank() && !BUCKETS.containsKey(bucket)) {
            throw com.anindra.path.api.ApiException.badRequest(
                    "bucket must be one of " + ACCEPTED_BUCKETS, "bucket");
        }
        Map<String, Object> out = projectId == null
                ? adminService.stats(parsed)
                : projectStats(projectId, parsed);
        if (bucket != null && !bucket.isBlank()) {
            out.put("bucket", bucket);
            out.put("series", series(projectId, parsed, bucket));
        }
        return out;
    }

    /** Accepted bucket sizes; the value is the width in seconds. */
    static final Map<String, Long> BUCKETS = Map.of(
            "1m", 60L,
            "5m", 300L,
            "1h", 3_600L,
            "1d", 86_400L);
    private static final String ACCEPTED_BUCKETS = "1m, 5m, 1h, 1d";

    /** A window never renders more than this many points. */
    static final int MAX_POINTS = 500;

    /**
     * One query for the whole window, bucketed in Java. The alternative — one query per
     * bucket — multiplies database round-trips by the number of points, and PostgreSQL-only
     * date functions would break the H2 test suite.
     */
    private List<Map<String, Object>> series(Long projectId, Duration window, String bucket) {
        Long width = BUCKETS.get(bucket);
        if (width == null) {
            throw com.anindra.path.api.ApiException.badRequest(
                    "bucket must be one of " + ACCEPTED_BUCKETS, "bucket");
        }

        long nowSeconds = Instant.now().getEpochSecond();
        // Align to the bucket grid so the newest point is the bucket containing "now".
        long end = (nowSeconds / width + 1) * width;
        int points = (int) Math.min(MAX_POINTS, Math.max(1, ceilDiv(window.getSeconds(), width)));
        long start = end - (long) points * width;

        List<Object[]> rows;
        if (projectId == null) {
            rows = usage.findSeriesSource(Instant.ofEpochSecond(start), Instant.ofEpochSecond(end));
        } else {
            List<Long> keyIds = projectKeyIds(projectId);
            rows = keyIds.isEmpty() ? List.of()
                    : usage.findSeriesSourceForKeys(keyIds,
                            Instant.ofEpochSecond(start), Instant.ofEpochSecond(end));
        }

        long[] requests = new long[points];
        long[] failures = new long[points];
        java.math.BigDecimal[] cost = new java.math.BigDecimal[points];
        List<List<Long>> latencies = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            cost[i] = java.math.BigDecimal.ZERO;
            latencies.add(new ArrayList<>());
        }

        for (Object[] row : rows) {
            Instant createdAt = (Instant) row[0];
            UsageRecord.Status status = (UsageRecord.Status) row[1];
            java.math.BigDecimal rowCost = (java.math.BigDecimal) row[2];
            Long latency = (Long) row[3];
            int index = (int) ((createdAt.getEpochSecond() - start) / width);
            if (index < 0 || index >= points) {
                continue;
            }
            requests[index]++;
            if (status == UsageRecord.Status.ERROR) {
                failures[index]++;
            } else if (latency != null) {
                latencies.get(index).add(latency);
            }
            if (rowCost != null) {
                cost[index] = cost[index].add(rowCost);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            List<Long> bucketLatencies = latencies.get(i);
            bucketLatencies.sort(null);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("from", Instant.ofEpochSecond(start + (long) i * width).toString());
            point.put("requests", requests[i]);
            point.put("cost_usd", cost[i]);
            point.put("failures", failures[i]);
            point.put("p95_latency_ms", percentile(bucketLatencies, 0.95));
            out.add(point);
        }
        return out;
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    /** Aggregates in Java from the project's rows; bounded so a huge project cannot OOM. */
    private Map<String, Object> projectStats(Long projectId, Duration window) {
        java.time.Instant from = java.time.Instant.now().minus(window);
        List<Long> keyIds = projectKeyIds(projectId);
        List<UsageRecord> rows = keyIds.isEmpty() ? List.of()
                : usage.findByClientKeyIdInAndCreatedAtAfterOrderByCreatedAtDesc(
                        keyIds, from, PageRequest.of(0, 50_000));

        List<Long> latencies = rows.stream()
                .filter(r -> r.getStatus() == UsageRecord.Status.SUCCESS)
                .map(UsageRecord::getLatencyMs).sorted().toList();
        List<Long> ttfts = rows.stream().map(UsageRecord::getTtftMs)
                .filter(java.util.Objects::nonNull).sorted().toList();
        List<java.math.BigDecimal> throughput = rows.stream().map(UsageRecord::getTokensPerSecond)
                .filter(java.util.Objects::nonNull).sorted().toList();

        long promptTokens = rows.stream().mapToLong(UsageRecord::getPromptTokens).sum();
        long cachedTokens = rows.stream().mapToLong(UsageRecord::getCachedInputTokens).sum();
        java.math.BigDecimal spend = rows.stream().map(UsageRecord::getCostUsd)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.math.BigDecimal savings = rows.stream().map(UsageRecord::getCachedSavingsUsd)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("window", window.toString());
        out.put("project_id", projectId);
        out.put("requests", rows.size());
        out.put("total_tokens", rows.stream().mapToLong(UsageRecord::getTotalTokens).sum());
        out.put("cost_usd", spend);
        out.put("latency_ms", Map.of("p50", percentile(latencies, 0.50),
                "p95", percentile(latencies, 0.95), "p99", percentile(latencies, 0.99)));
        out.put("ttft_ms", Map.of("p50", percentile(ttfts, 0.50), "p95", percentile(ttfts, 0.95)));

        Map<String, Object> cache = new java.util.LinkedHashMap<>();
        cache.put("prompt_tokens", promptTokens);
        cache.put("cached_input_tokens", cachedTokens);
        cache.put("hit_ratio", promptTokens == 0 ? 0.0
                : java.math.BigDecimal.valueOf(cachedTokens)
                        .divide(java.math.BigDecimal.valueOf(promptTokens), 4,
                                java.math.RoundingMode.HALF_UP).doubleValue());
        cache.put("savings_usd", savings);
        cache.put("semantic_hits", rows.stream()
                .filter(r -> "semantic".equals(r.getCacheTier())).count());
        cache.put("exact_hits", rows.stream()
                .filter(r -> "exact".equals(r.getCacheTier())).count());
        out.put("cache", cache);
        out.put("fallback_requests", rows.stream().filter(r -> r.getAttempts() > 1).count());
        out.put("estimated_usage_requests", rows.stream().filter(UsageRecord::isUsageEstimated).count());

        Map<String, Object> byProvider = new java.util.LinkedHashMap<>();
        rows.stream().filter(r -> r.getProviderSlug() != null)
                .collect(java.util.stream.Collectors.groupingBy(UsageRecord::getProviderSlug))
                .forEach((slug, list) -> byProvider.put(slug, Map.of(
                        "requests", list.size(),
                        "cost_usd", list.stream().map(UsageRecord::getCostUsd)
                                .filter(java.util.Objects::nonNull)
                                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))));
        out.put("by_provider", byProvider);

        List<Map<String, Object>> byModel = new java.util.ArrayList<>();
        rows.stream().filter(r -> r.getPublicModelId() != null)
                .collect(java.util.stream.Collectors.groupingBy(UsageRecord::getPublicModelId))
                .forEach((model, list) -> byModel.add(Map.of(
                        "model", model,
                        "requests", list.size(),
                        "tokens", list.stream().mapToLong(UsageRecord::getTotalTokens).sum(),
                        "cost_usd", list.stream().map(UsageRecord::getCostUsd)
                                .filter(java.util.Objects::nonNull)
                                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))));
        out.put("by_model", byModel);
        return out;
    }

    private List<Long> projectKeyIds(Long projectId) {
        return keys.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(com.anindra.path.domain.ClientKey::getId).toList();
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
