package com.anindra.path.admin;

import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.PromptLog;
import com.anindra.path.repo.PromptLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Off by default. When enabled per gateway, stores redacted request/response bodies for
 * a limited retention window so support can reproduce a customer call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromptLogService {

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(sk-[a-z0-9]{10,}|gsk_[a-z0-9]{10,}|sk-ant-[a-z0-9_-]{10,}|bearer\\s+[a-z0-9._-]{10,})");

    private final PromptLogRepository logs;
    private final GatewayProperties props;

    public boolean enabled() {
        return props.getPromptLogging().isEnabled();
    }

    public void record(Long clientKeyId, String requestId, String model, String request, String response) {
        if (!enabled()) {
            return;
        }
        try {
            int max = props.getPromptLogging().getMaxBodyBytes();
            PromptLog entry = new PromptLog();
            entry.setClientKeyId(clientKeyId);
            entry.setRequestId(requestId);
            entry.setPublicModelId(model);
            entry.setRequestBody(truncate(redact(request), max));
            entry.setResponseBody(truncate(redact(response), max));
            entry.setExpiresAt(Instant.now().plus(props.getPromptLogging().getRetention()));
            logs.save(entry);
        } catch (Exception e) {
            log.warn("Could not store prompt log: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> list(Long clientKeyId, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        List<PromptLog> rows = clientKeyId == null
                ? logs.findAllByOrderByCreatedAtDesc(PageRequest.of(0, capped))
                : logs.findByClientKeyIdOrderByCreatedAtDesc(clientKeyId, PageRequest.of(0, capped));

        List<Map<String, Object>> out = new ArrayList<>();
        for (PromptLog row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("at", row.getCreatedAt().toString());
            item.put("key_id", row.getClientKeyId());
            item.put("request_id", row.getRequestId());
            item.put("model", row.getPublicModelId());
            item.put("request", row.getRequestBody());
            item.put("response", row.getResponseBody());
            out.add(item);
        }
        return out;
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    void purgeExpired() {
        long removed = logs.deleteByExpiresAtBefore(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired prompt logs", removed);
        }
    }

    static String redact(String body) {
        return body == null ? null : SECRET.matcher(body).replaceAll("[redacted]");
    }

    private static String truncate(String body, int maxBytes) {
        if (body == null || body.length() <= maxBytes) {
            return body;
        }
        return body.substring(0, maxBytes) + "…[truncated]";
    }
}
