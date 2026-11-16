package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.domain.ClientKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Refuses a request before any upstream call when the key is out of credit or the
 * worst-case cost of the call could exceed what is left.
 */
@Component
@RequiredArgsConstructor
public class CreditGuard {

    private static final int DEFAULT_MAX_OUTPUT = 1024;
    private static final int ESTIMATE_CAP = 8192;

    private final ApiKeyService apiKeys;
    private final UsageService usage;

    public void check(ClientKey key, ChatRequestSpec spec, RouteResolver.ResolvedRoute route) {
        BigDecimal remaining = apiKeys.remaining(key);
        if (remaining == null) {
            return;
        }
        if (remaining.signum() <= 0) {
            throw ApiException.insufficientCredits(
                    "This API key has no credit left (" + key.getUsedUsd() + " of "
                            + key.getCreditLimitUsd() + " USD used). Top up the key to continue.");
        }
        int promptEstimate = spec.estimatePromptTokens();
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
}
