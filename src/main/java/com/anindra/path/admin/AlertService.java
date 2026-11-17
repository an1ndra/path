package com.anindra.path.admin;

import com.anindra.path.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fire-and-forget operational alerts (Slack-compatible JSON) with a per-event cooldown.
 * A blank webhook URL makes every call a no-op.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertService {

    private final GatewayProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private final Map<String, Long> sentOnce = new ConcurrentHashMap<>();

    /**
     * Fires at most once per {@code dedupeKey}, regardless of the cooldown. Used for budget
     * thresholds, where the key carries the period (project, window, day or month) so a
     * busy project cannot emit thousands of identical events.
     */
    public void notifyOnce(String dedupeKey, String event, Map<String, Object> payload) {
        String url = props.getAlerts().getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        if (sentOnce.putIfAbsent(dedupeKey, System.currentTimeMillis()) != null) {
            return;
        }
        notify(event, payload);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3_600_000)
    void forgetOldDedupeKeys() {
        long cutoff = System.currentTimeMillis() - java.time.Duration.ofDays(2).toMillis();
        sentOnce.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    public void notify(String event, Map<String, Object> payload) {
        String url = props.getAlerts().getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldownMs = props.getAlerts().getCooldown().toMillis();
        Long previous = lastSent.putIfAbsent(event, now);
        if (previous != null) {
            if (now - previous < cooldownMs) {
                return;
            }
            lastSent.put(event, now);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "path");
        body.put("event", event);
        body.put("at", Instant.now().toString());
        body.putAll(payload);

        Thread.ofVirtual().name("alert-webhook").start(() -> send(url, body));
    }

    private void send(String url, Map<String, Object> body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("Alert webhook failed for {}: {}", body.get("event"), e.getMessage());
        }
    }
}
