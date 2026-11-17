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

    public Map<String, Object> usage(Long keyId, String window, int limit) {
        Duration parsed = Durations.cap(Durations.parse(window, Duration.ofHours(24)), Duration.ofDays(365));
        int capped = Math.max(1, Math.min(limit, 500));
        List<UsageRecord> records = keyId != null
                ? usage.findByClientKeyIdOrderByCreatedAtDesc(keyId, PageRequest.of(0, capped))
                : usage.findByCreatedAtAfterOrderByCreatedAtDesc(
                        java.time.Instant.now().minus(parsed), PageRequest.of(0, capped));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (UsageRecord r : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("at", r.getCreatedAt().toString());
            row.put("key_id", r.getClientKeyId());
            row.put("key_name", r.getClientKeyName());
            row.put("model", r.getPublicModelId());
            row.put("provider", r.getProviderSlug());
            row.put("upstream_model", r.getUpstreamModelId());
            row.put("prompt_tokens", r.getPromptTokens());
            row.put("completion_tokens", r.getCompletionTokens());
            row.put("total_tokens", r.getTotalTokens());
            row.put("cached_input_tokens", r.getCachedInputTokens());
            row.put("cache_write_tokens", r.getCacheWriteTokens());
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
        out.put("count", rows.size());
        out.put("data", rows);
        return out;
    }

    /** Comma-separated statement of calls, one row per request, oldest first. */
    public String usageCsv(Long keyId, java.time.Instant from, java.time.Instant to, int limit) {
        int capped = Math.max(1, Math.min(limit, 50_000));
        List<UsageRecord> rows = usage.findByCreatedAtBetweenOrderByCreatedAtAsc(
                from, to, PageRequest.of(0, capped));

        StringBuilder csv = new StringBuilder();
        csv.append("created_at,key_id,key_name,model,provider,upstream_model,prompt_tokens,")
                .append("completion_tokens,cached_input_tokens,cache_write_tokens,reasoning_tokens,")
                .append("input_cost_usd,cached_input_cost_usd,cache_write_cost_usd,output_cost_usd,")
                .append("cost_usd,latency_ms,ttft_ms,streamed,status,error_code\n");

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
                    .append(escape(r.getErrorCode()))
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

    public Map<String, Object> stats(String window) {
        Duration parsed = Durations.cap(Durations.parse(window, Duration.ofHours(24)), Duration.ofDays(365));
        return adminService.stats(parsed);
    }
}
