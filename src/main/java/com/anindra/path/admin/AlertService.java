package com.anindra.path.admin;

import com.anindra.path.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
    /**
     * Only present when spring.mail.host is configured. ObjectProvider keeps the gateway
     * bootable with email alerting off, which is the default.
     */
    private final ObjectProvider<JavaMailSender> mailSender;
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
        if ((url == null || url.isBlank()) && !emailEnabled()) {
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
        boolean wantsEmail = emailEnabled();
        if ((url == null || url.isBlank()) && !wantsEmail) {
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

        if (url != null && !url.isBlank()) {
            Thread.ofVirtual().name("alert-webhook").start(() -> send(url, body));
        }
        if (wantsEmail) {
            Thread.ofVirtual().name("alert-email").start(() -> email(body));
        }
    }

    /** True when recipients are configured and the mail client exists. */
    public boolean emailEnabled() {
        return props.getAlerts().getEmailTo() != null
                && !props.getAlerts().getEmailTo().isBlank()
                && mailSender.getIfAvailable() != null;
    }

    public boolean webhookEnabled() {
        return props.getAlerts().getWebhookUrl() != null
                && !props.getAlerts().getWebhookUrl().isBlank();
    }

    private void email(Map<String, Object> body) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(props.getAlerts().getEmailFrom());
            message.setTo(props.getAlerts().getEmailTo().split("\\s*,\\s*"));
            message.setSubject("[Path] " + body.get("event"));
            message.setText(text(body));
            sender.send(message);
        } catch (Exception e) {
            // An unreachable mailbox must never affect the request that triggered the alert.
            log.warn("Alert email failed for {}: {}", body.get("event"), e.getMessage());
        }
    }

    /** Plain text body: the event, the time, then one {@code key: value} line per field. */
    static String text(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder();
        sb.append("Path gateway alert\n\n");
        sb.append("event: ").append(body.get("event")).append('\n');
        sb.append("at:    ").append(body.get("at")).append('\n');
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (entry.getKey().equals("source") || entry.getKey().equals("event")
                    || entry.getKey().equals("at")) {
                continue;
            }
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
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
