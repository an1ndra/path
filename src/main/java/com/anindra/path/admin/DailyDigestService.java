package com.anindra.path.admin;

import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.UsageRecord;
import com.anindra.path.gateway.ProviderCircuitBreaker;
import com.anindra.path.repo.ProviderRepository;
import com.anindra.path.repo.UsageRecordRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One email a day describing yesterday: requests, spend, tokens, failures, the slowest
 * model, the biggest spenders and any provider still parked.
 *
 * <p>Threshold alerts only fire when something is already wrong, and nobody opens a
 * dashboard every morning; a slow spend drift is otherwise noticed on the invoice. The
 * digest reuses the alert mail channel and is silently off when no recipients are
 * configured.
 *
 * <p>"Yesterday" is the previous calendar day in the gateway's time zone, not the last 24
 * hours, so a digest at 08:00 covers the day an operator thinks of as yesterday.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyDigestService {

    /** A successful-call sample used for the per-model p95; bounded like the stats view. */
    private static final int LATENCY_SAMPLE = 50_000;
    private static final int TOP_MODELS = 3;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final UsageRecordRepository usage;
    private final MailSettingsService mail;
    private final GatewayProperties props;
    private final ProviderCircuitBreaker breaker;
    private final ProviderRepository providers;
    private final MeterRegistry metrics;

    /** Yesterday's numbers, enough to render the email. */
    public record Day(long requests, long failures, BigDecimal cost, long tokens,
                      long promptTokens, long cachedTokens, BigDecimal savings,
                      String topErrorCode, long topErrorCount,
                      String slowestModel, long slowestP95Ms,
                      List<ModelSpend> topModels) {
    }

    public record ModelSpend(String model, BigDecimal cost) {
    }

    /** What the send did, so the admin endpoint can explain itself. */
    public record Result(boolean sent, String reason, String subject, String text) {
    }

    @Scheduled(cron = "${gateway.digest.cron:0 0 8 * * *}")
    public void scheduled() {
        if (!props.getDigest().isEnabled()) {
            return;
        }
        send(LocalDate.now().minusDays(1));
    }

    /**
     * Builds and sends the digest for {@code day}. Sends nothing when disabled, when no
     * mailbox is configured, or when the day had no traffic; the caller gets the reason.
     */
    public Result send(LocalDate day) {
        if (!props.getDigest().isEnabled()) {
            return new Result(false, "disabled", null, null);
        }
        ZoneId zone = ZoneId.systemDefault();
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();

        Day today = collect(from, to);
        if (today.requests() == 0) {
            return new Result(false, "no_traffic", null, null);
        }
        Day previous = collect(from.minusSeconds(86_400), from);
        String subject = subject(today);
        String text = render(day, zone, today, previous, parkedNow());

        if (!mail.emailEnabled()) {
            return new Result(false, "email_not_configured", subject, text);
        }
        JavaMailSender sender = mail.sender().orElse(null);
        if (sender == null) {
            return new Result(false, "email_not_configured", subject, text);
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mail.fromAddress());
            message.setTo(mail.recipients().split("\\s*,\\s*"));
            message.setSubject(subject);
            message.setText(text);
            sender.send(message);
            metrics.counter("path.alerts.sent", "channel", "digest", "outcome", "delivered").increment();
            return new Result(true, null, subject, text);
        } catch (Exception e) {
            // A broken mailbox must never stop tomorrow's digest.
            metrics.counter("path.alerts.sent", "channel", "digest", "outcome", "dropped").increment();
            log.warn("Daily digest for {} could not be sent: {}", day, e.getMessage());
            return new Result(false, "send_failed", subject, text);
        }
    }

    // --------------------------------------------------------------- gathering

    private Day collect(Instant from, Instant to) {
        long requests = usage.countByCreatedAtBetween(from, to);
        long failures = usage.countByStatusAndCreatedAtBetween(UsageRecord.Status.ERROR, from, to);
        BigDecimal cost = nz(usage.sumCostBetween(from, to));
        long tokens = nz(usage.sumTokensBetween(from, to));
        long promptTokens = nz(usage.sumPromptTokensBetween(from, to));
        long cachedTokens = nz(usage.sumCachedTokensBetween(from, to));
        BigDecimal savings = nz(usage.sumCachedSavingsBetween(from, to));

        String topError = null;
        long topErrorCount = 0;
        List<Object[]> errors = usage.topErrorCodesBetween(from, to, PageRequest.of(0, 1));
        if (!errors.isEmpty()) {
            topError = String.valueOf(errors.get(0)[0]);
            topErrorCount = ((Number) errors.get(0)[1]).longValue();
        }

        List<ModelSpend> topModels = new ArrayList<>();
        for (Object[] row : usage.topModelsByCostBetween(from, to, PageRequest.of(0, TOP_MODELS))) {
            topModels.add(new ModelSpend(String.valueOf(row[0]), (BigDecimal) row[1]));
        }

        String slowestModel = null;
        long slowestP95 = 0;
        Map<String, List<Long>> latencies = new LinkedHashMap<>();
        for (Object[] row : usage.successLatenciesBetween(from, to, PageRequest.of(0, LATENCY_SAMPLE))) {
            if (row[1] == null) {
                continue;
            }
            latencies.computeIfAbsent(String.valueOf(row[0]), k -> new ArrayList<>())
                    .add(((Number) row[1]).longValue());
        }
        for (Map.Entry<String, List<Long>> entry : latencies.entrySet()) {
            List<Long> sorted = entry.getValue();
            sorted.sort(null);
            long p95 = p95(sorted);
            if (p95 > slowestP95) {
                slowestP95 = p95;
                slowestModel = entry.getKey();
            }
        }

        return new Day(requests, failures, cost, tokens, promptTokens, cachedTokens, savings,
                topError, topErrorCount, slowestModel, slowestP95, topModels);
    }

    /** Providers whose circuit is open right now; the breaker keeps no history to count. */
    private List<String> parkedNow() {
        List<String> parked = new ArrayList<>();
        Map<String, Object> snapshot = breaker.snapshot();
        for (Provider provider : providers.findAllByOrderBySlugAsc()) {
            Object state = snapshot.get(String.valueOf(provider.getId()));
            if (state instanceof Map<?, ?> view && "open".equals(view.get("state"))) {
                parked.add(provider.getSlug());
            }
        }
        return parked;
    }

    // --------------------------------------------------------------- rendering

    static String subject(Day day) {
        return "[Path] yesterday: " + count(day.requests()) + " requests, "
                + money(day.cost()) + ", " + percent(failureRatio(day)) + " failed";
    }

    static String render(LocalDate day, ZoneId zone, Day today, Day previous, List<String> parked) {
        StringBuilder sb = new StringBuilder();
        sb.append("Path daily digest — ").append(DAY.format(day))
                .append(" (").append(zone.getId()).append(")\n\n");
        sb.append(row("Requests", count(today.requests()) + "        (" + requestDelta(today, previous) + ")"));
        sb.append(row("Spend", money(today.cost()) + "        (" + spendDelta(today, previous) + ")"));
        String cache = today.promptTokens() == 0 ? "cache hit n/a"
                : "cache hit " + percent(ratio(today.cachedTokens(), today.promptTokens()))
                + ", saved " + money(today.savings());
        sb.append(row("Tokens", tokens(today.tokens()) + "          " + cache));
        String failures = percent(failureRatio(today));
        if (today.topErrorCode() != null) {
            failures += "  (top: " + today.topErrorCode() + " ×" + count(today.topErrorCount()) + ")";
        }
        sb.append(row("Failures", count(today.failures()) + "           " + failures));
        sb.append(row("Slowest", today.slowestModel() == null ? "—"
                : today.slowestModel() + "  p95 " + seconds(today.slowestP95Ms())));
        List<String> spenders = new ArrayList<>();
        for (ModelSpend model : today.topModels()) {
            spenders.add(model.model() + " " + money(model.cost()));
        }
        sb.append(row("Top spend", spenders.isEmpty() ? "—" : String.join(" · ", spenders)));
        if (!parked.isEmpty()) {
            sb.append(row("Parked", String.join(", ", parked) + " (circuit open now)"));
        }
        return sb.toString();
    }

    /** Label left-aligned in 14 columns, the same shape as the alert email. */
    private static String row(String label, String value) {
        return String.format(Locale.ROOT, "%-14s%s%n", label, value);
    }

    private static String requestDelta(Day today, Day previous) {
        if (previous.requests() == 0) {
            return "no traffic the day before";
        }
        double change = (today.requests() - previous.requests()) * 100.0 / previous.requests();
        return String.format(Locale.ROOT, "%+.0f%% vs the day before", change);
    }

    private static String spendDelta(Day today, Day previous) {
        BigDecimal change = today.cost().subtract(previous.cost());
        return String.format(Locale.ROOT, "%s%s", change.signum() >= 0 ? "+" : "-",
                money(change.abs()));
    }

    private static double failureRatio(Day day) {
        return day.requests() == 0 ? 0 : (double) day.failures() / day.requests();
    }

    private static double ratio(long part, long whole) {
        return whole == 0 ? 0 : (double) part / whole;
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String tokens(long value) {
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 10_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        }
        return count(value);
    }

    private static String money(BigDecimal value) {
        return String.format(Locale.ROOT, "$%,.2f", value == null ? BigDecimal.ZERO : value);
    }

    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    private static long p95(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
