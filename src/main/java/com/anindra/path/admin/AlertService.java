package com.anindra.path.admin;

import com.anindra.path.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Operational alerts (Slack-compatible JSON) with a per-event cooldown.
 *
 * <p>Webhook delivery is retried with exponential backoff and jitter, because a single
 * fire-and-forget POST silently loses an alert during a 500, a 429 or a two-second network
 * blip. Only 429, 5xx and connect/timeout failures are retried; a 4xx is a permanent
 * rejection and retrying it would just repeat the same mistake.
 *
 * <p>Sends run on a small bounded executor. If the webhook is down and the queue fills, new
 * alerts are dropped (and counted) rather than piling up behind a dead endpoint. A blank
 * webhook URL makes the webhook channel a no-op; email is independent.
 */
@Service
@Slf4j
public class AlertService {

    /** One initial attempt plus two retries. */
    private static final int MAX_ATTEMPTS = 3;
    /** Exponential backoff before the next attempt: 250ms, 1s, then (unused) 4s. */
    private static final long[] BACKOFF_MS = {250, 1_000, 4_000};
    /** A Retry-After larger than this is capped so one endpoint cannot pin a worker. */
    private static final long MAX_RETRY_AFTER_MS = 30_000;
    private static final int WORKERS = 2;
    private static final int QUEUE_CAPACITY = 64;

    private final GatewayProperties props;
    private final ObjectMapper mapper;
    /** Console-configured (DB) mail settings, falling back to SPRING_MAIL_*. */
    private final MailSettingsService mailSettings;
    private final MeterRegistry metrics;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private final Map<String, Long> sentOnce = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor webhookExecutor;

    public AlertService(GatewayProperties props, ObjectMapper mapper,
                        MailSettingsService mailSettings, MeterRegistry metrics) {
        this(props, mapper, mailSettings, metrics, WORKERS, QUEUE_CAPACITY);
    }

    /** Test seam: a tiny executor makes the dropped-alert path reachable. */
    AlertService(GatewayProperties props, ObjectMapper mapper, MailSettingsService mailSettings,
                 MeterRegistry metrics, int workers, int queueCapacity) {
        this.props = props;
        this.mapper = mapper;
        this.mailSettings = mailSettings;
        this.metrics = metrics;
        this.webhookExecutor = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable, "alert-webhook");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, executor) -> {
                    count("dropped");
                    log.warn("Alert webhook queue is full; dropping an alert instead of blocking the caller");
                });
        this.webhookExecutor.allowCoreThreadTimeOut(true);
    }

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
            enqueueWebhook(url, body);
        }
        if (wantsEmail) {
            Thread.ofVirtual().name("alert-email").start(() -> email(body));
        }
    }

    /** True when recipients are configured and a sender can be built. */
    public boolean emailEnabled() {
        return mailSettings.emailEnabled();
    }

    public boolean webhookEnabled() {
        return props.getAlerts().getWebhookUrl() != null
                && !props.getAlerts().getWebhookUrl().isBlank();
    }

    private void enqueueWebhook(String url, Map<String, Object> body) {
        try {
            webhookExecutor.execute(() -> send(url, body));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The handler above counts the drop, but it is not reached while the pool is
            // shutting down (for example during a drain).
            count("dropped");
            log.warn("Alert webhook executor rejected {} while shutting down", body.get("event"));
        }
    }

    private void email(Map<String, Object> body) {
        JavaMailSender sender = mailSettings.sender().orElse(null);
        if (sender == null) {
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailSettings.fromAddress());
            message.setTo(mailSettings.recipients().split("\\s*,\\s*"));
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

    /**
     * Delivers one alert, retrying a transient failure up to {@link #MAX_ATTEMPTS} times.
     * Counting happens once per outcome for the whole alert: {@code delivered} when a 2xx
     * arrives, {@code retried} once per retry, {@code dropped} when it is given up on.
     */
    private void send(String url, Map<String, Object> body) {
        String json;
        URI uri;
        try {
            json = mapper.writeValueAsString(body);
            uri = URI.create(url);
        } catch (Exception e) {
            count("dropped");
            log.warn("Alert webhook payload is not serializable for {}: {}", body.get("event"), e.getMessage());
            return;
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long retryAfterMs = -1;
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(java.time.Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    count("delivered");
                    return;
                }
                if (!retryable(status)) {
                    count("dropped");
                    log.warn("Alert webhook rejected {} with {}; not retrying a permanent failure",
                            body.get("event"), status);
                    return;
                }
                retryAfterMs = retryAfterMs(response);
                if (attempt == MAX_ATTEMPTS) {
                    count("dropped");
                    log.warn("Alert webhook failed for {} after {} attempts (last status {})",
                            body.get("event"), attempt, status);
                    return;
                }
                log.warn("Alert webhook for {} returned {}; retrying (attempt {}/{})",
                        body.get("event"), status, attempt + 1, MAX_ATTEMPTS);
            } catch (IOException e) {
                // Connect failures, timeouts and a dropped connection are all transient.
                if (attempt == MAX_ATTEMPTS) {
                    count("dropped");
                    log.warn("Alert webhook failed for {} after {} attempts: {}",
                            body.get("event"), attempt, e.getMessage());
                    return;
                }
                log.warn("Alert webhook for {} failed: {}; retrying (attempt {}/{})",
                        body.get("event"), e.getMessage(), attempt + 1, MAX_ATTEMPTS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                count("dropped");
                log.warn("Alert webhook for {} was interrupted", body.get("event"));
                return;
            } catch (Exception e) {
                count("dropped");
                log.warn("Alert webhook failed for {}: {}", body.get("event"), e.getMessage());
                return;
            }

            count("retried");
            long delay = retryAfterMs >= 0 ? Math.min(retryAfterMs, MAX_RETRY_AFTER_MS)
                    : jittered(attempt);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                count("dropped");
                return;
            }
        }
        count("dropped");
    }

    /** Only rate limits and server-side failures are worth another attempt. */
    private static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    /** 250ms, 1s (then 4s) with jitter in the upper half of that window. */
    private static long jittered(int attempt) {
        long base = BACKOFF_MS[Math.min(attempt - 1, BACKOFF_MS.length - 1)];
        return base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1);
    }

    /** Seconds or an HTTP date, per RFC 9110. Returns -1 when absent or unparseable. */
    private static long retryAfterMs(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse(null);
        if (value == null || value.isBlank()) {
            return -1;
        }
        String trimmed = value.trim();
        try {
            return Math.max(0, Long.parseLong(trimmed) * 1_000);
        } catch (NumberFormatException ignored) {
            // Fall through to the HTTP-date form.
        }
        try {
            ZonedDateTime at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0, at.toInstant().toEpochMilli() - System.currentTimeMillis());
        } catch (DateTimeParseException ignored) {
            return -1;
        }
    }

    private void count(String outcome) {
        metrics.counter("path.alerts.sent", "outcome", outcome).increment();
    }
}
