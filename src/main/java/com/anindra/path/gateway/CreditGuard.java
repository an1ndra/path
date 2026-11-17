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

    private final ApiKeyService apiKeys;
    private final UsageService usage;
    private final GatewayProperties props;
    private final com.anindra.path.admin.AlertService alerts;

    public void check(ClientKey key, ChatRequestSpec spec, RouteResolver.ResolvedRoute route) {
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

        BigDecimal remaining = apiKeys.remaining(key);
        if (remaining == null) {
            return;
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
        if (worstCase.compareTo(remaining) > 0) {
            throw ApiException.insufficientCredits(
                    "This request could cost up to " + worstCase.toPlainString()
                            + " USD but only " + remaining.toPlainString()
                            + " USD of credit remains. Lower max_tokens or top up the key.");
        }
    }

    /** Comma-separated ids or globs; blank means every model. */
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
