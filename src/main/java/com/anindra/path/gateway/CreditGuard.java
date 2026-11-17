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

    public void check(ClientKey key, ChatRequestSpec spec, RouteResolver.ResolvedRoute route) {
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
