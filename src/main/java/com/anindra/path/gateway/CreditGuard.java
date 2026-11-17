package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ClientKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Refuses a request before any upstream call when the key is out of credit, has
 * exhausted its daily or monthly window, or when the worst-case cost of the call
 * could exceed what is left. Also applies the output-token cap.
 */
@Component
@RequiredArgsConstructor
public class CreditGuard {

    private static final int DEFAULT_MAX_OUTPUT = 1024;
    private static final int ESTIMATE_CAP = 8192;
    private static final int EXPECTED_OUTPUT = 512;

    private final ApiKeyService apiKeys;
    private final UsageService usage;
    private final GatewayProperties props;
    private final com.anindra.path.admin.AlertService alerts;
    private final com.anindra.path.repo.ProjectRepository projects;

    /** @return the amount reserved for this call; release it when the call ends. */
    public BigDecimal check(ClientKey key, ChatRequestSpec spec, RouteResolver.ResolvedRoute route) {
        if (!allows(key, spec.publicModel())) {
            throw new ApiException(403, "permission_error", "model_not_allowed",
                    "This API key is not allowed to use model '" + spec.publicModel() + "'.");
        }
        Integer cap = key.getMaxOutputTokens() != null && key.getMaxOutputTokens() > 0
                ? key.getMaxOutputTokens()
                : (props.getMaxOutputTokens() > 0 ? props.getMaxOutputTokens() : null);
        spec.clampMaxTokens(cap);

        Instant now = Instant.now();
        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        enforceWindow("Daily", key.getDailyLimitUsd(), usage.spendFor(key.getId(), dayStart), dayStart, now);
        enforceWindow("Monthly", key.getMonthlyLimitUsd(), usage.spendFor(key.getId(), monthStart), monthStart, now);

        if (key.getProjectId() != null) {
            projects.findById(key.getProjectId()).ifPresent(project -> {
                enforceProjectWindow(project, "daily", project.getDailyLimitUsd(),
                        usage.spendForProject(project.getId(), dayStart), dayStart, now);
                enforceProjectWindow(project, "monthly", project.getMonthlyLimitUsd(),
                        usage.spendForProject(project.getId(), monthStart), monthStart, now);
                if (project.getCreditLimitUsd() != null && project.getCreditLimitUsd().signum() > 0) {
                    BigDecimal projectSpend = usage.spendForProject(project.getId(),
                            Instant.EPOCH);
                    warnProject(project, "lifetime", project.getCreditLimitUsd(), projectSpend,
                            "lifetime");
                    if (projectSpend.compareTo(project.getCreditLimitUsd()) >= 0) {
                        exhaustedProject(project, "lifetime", project.getCreditLimitUsd(),
                                projectSpend, "lifetime");
                        throw ApiException.insufficientCredits(
                                "Project '" + project.getSlug() + "' has exhausted its credit limit of "
                                        + project.getCreditLimitUsd().toPlainString() + " USD.");
                    }
                }
            });
        }

        BigDecimal remaining = apiKeys.remaining(key);
        if (remaining == null) {
            return BigDecimal.ZERO;
        }
        if (key.getCreditLimitUsd() != null && key.getCreditLimitUsd().signum() > 0) {
            double left = remaining.divide(key.getCreditLimitUsd(), 4, java.math.RoundingMode.HALF_UP).doubleValue();
            if (left < props.getAlerts().getBudgetThreshold()) {
                alerts.notify("key.budget_low", Map.of(
                        "key_id", key.getId(),
                        "key_name", key.getName(),
                        "remaining_usd", remaining,
                        "limit_usd", key.getCreditLimitUsd(),
                        "fraction", left));
            }
        }
        if (remaining.signum() <= 0) {
            throw ApiException.insufficientCredits(
                    "This API key has no credit left (" + key.getUsedUsd() + " of "
                            + key.getCreditLimitUsd() + " USD used). Top up the key to continue.");
        }
        int promptEstimate = spec.estimatePromptTokens(route.route().getUpstreamModelId());
        int outputEstimate = spec.maxTokens() != null ? spec.maxTokens() : DEFAULT_MAX_OUTPUT;
        outputEstimate = Math.min(outputEstimate, ESTIMATE_CAP);
        BigDecimal worstCase = usage.cost(route.route(),
                TokenUsage.of(promptEstimate, outputEstimate)).totalUsd();
        // Reserve what a typical completion would cost, not the absolute cap: a small
        // balance should not be blocked by a generous max_tokens it will never use.
        int expectedOutput = Math.min(outputEstimate, EXPECTED_OUTPUT);
        BigDecimal expected = usage.cost(route.route(),
                TokenUsage.of(promptEstimate, expectedOutput)).totalUsd();

        if (expected.compareTo(remaining) > 0) {
            throw ApiException.insufficientCredits(
                    "This request is expected to cost about " + expected.toPlainString()
                            + " USD but only " + remaining.toPlainString()
                            + " USD of credit remains. Lower max_tokens or top up the key.");
        }
        BigDecimal reserve = worstCase.min(remaining);
        if (!apiKeys.reserve(key.getId(), reserve)) {
            throw ApiException.insufficientCredits(
                    "Credit is exhausted by concurrent requests. Retry shortly or top up the key.");
        }
        return reserve;
    }

    /** Comma-separated ids or globs; blank means every model. */
    private void enforceProjectWindow(com.anindra.path.domain.Project project, String window,
                                      BigDecimal limit, BigDecimal spent, Instant periodStart,
                                      Instant now) {
        if (limit == null || limit.signum() <= 0) {
            return;
        }
        warnProject(project, window, limit, spent, periodKey(window, periodStart));
        if (spent.compareTo(limit) >= 0) {
            exhaustedProject(project, window, limit, spent, periodKey(window, periodStart));
            throw ApiException.insufficientCredits(
                    "Project '" + project.getSlug() + "' " + window + " budget of "
                            + limit.toPlainString() + " USD is exhausted (" + spent.toPlainString()
                            + " USD spent). Raise the limit or wait for the window to reset.");
        }
    }

    private void warnProject(com.anindra.path.domain.Project project, String window,
                             BigDecimal limit, BigDecimal spent, String period) {
        double remainingFraction = limit.subtract(spent)
                .divide(limit, 4, java.math.RoundingMode.HALF_UP).doubleValue();
        if (remainingFraction >= props.getAlerts().getBudgetThreshold()) {
            return;
        }
        alerts.notifyOnce("project.budget_low:" + project.getId() + ":" + period,
                "project.budget_low", projectPayload(project, window, limit, spent));
    }

    private void exhaustedProject(com.anindra.path.domain.Project project, String window,
                                  BigDecimal limit, BigDecimal spent, String period) {
        alerts.notifyOnce("project.budget_exhausted:" + project.getId() + ":" + period,
                "project.budget_exhausted", projectPayload(project, window, limit, spent));
    }

    private static Map<String, Object> projectPayload(com.anindra.path.domain.Project project,
                                                      String window, BigDecimal limit,
                                                      BigDecimal spent) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("project_id", project.getId());
        payload.put("project", project.getSlug());
        payload.put("window", window);
        payload.put("limit_usd", limit);
        payload.put("spent_usd", spent);
        payload.put("remaining_usd", limit.subtract(spent).max(BigDecimal.ZERO));
        return payload;
    }

    private static String periodKey(String window, Instant periodStart) {
        return window + "@" + periodStart;
    }

    static boolean allows(ClientKey key, String model) {
        String allowed = key.getAllowedModels();
        if (allowed == null || allowed.isBlank()) {
            return true;
        }
        for (String raw : allowed.split(",")) {
            String pattern = raw.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            if (pattern.equals("*") || pattern.equalsIgnoreCase(model)) {
                return true;
            }
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (model.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void enforceWindow(String label, BigDecimal limit, BigDecimal spent,
                                      Instant windowStart, Instant now) {
        if (limit == null || limit.signum() <= 0) {
            return;
        }
        if (spent.compareTo(limit) >= 0) {
            throw ApiException.insufficientCredits(
                    label + " budget of " + limit.toPlainString() + " USD is exhausted ("
                            + spent.toPlainString() + " USD spent since " + windowStart + ").");
        }
    }
}
