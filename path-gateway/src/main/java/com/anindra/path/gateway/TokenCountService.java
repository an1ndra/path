package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ClientKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /v1/tokens/count}: answers "will this prompt fit?" without spending a call.
 *
 * <p>Free, but authenticated and bounded exactly like a chat request: the model allowlist,
 * the drain state, the concurrency limiter and the RPM limiter all apply, so it cannot be
 * used to enumerate the catalogue or to bypass a key's restrictions.
 */
@Service
@RequiredArgsConstructor
public class TokenCountService {

    private final RouteResolver resolver;
    private final GatewayProperties props;

    public Map<String, Object> count(ObjectNode body, ClientKey key) {
        JsonNode messages = body.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            throw ApiException.badRequest("'messages' must be a non-empty array.", "messages");
        }
        ChatRequestSpec spec = ChatRequestSpec.forCounting(body);
        if (!CreditGuard.allows(key, spec.publicModel())) {
            throw new ApiException(403, "permission_error", "model_not_allowed",
                    "This API key is not allowed to use model '" + spec.publicModel() + "'.");
        }

        List<RouteResolver.ResolvedRoute> chain = resolver.resolve(spec, key.getId());
        if (chain.isEmpty()) {
            throw ApiException.notFound(
                    "The model '" + spec.publicModel() + "' does not exist.", "model_not_found");
        }

        RouteResolver.ResolvedRoute first = chain.get(0);
        String upstreamModel = first.route().getUpstreamModelId();
        TokenCounter.Count count = TokenCounter.count(body, upstreamModel);
        Integer contextWindow = first.route().getContextLength();

        int maxOutput = key.getMaxOutputTokens() != null && key.getMaxOutputTokens() > 0
                ? key.getMaxOutputTokens()
                : props.getMaxOutputTokens();
        if (maxOutput <= 0) {
            // No ceiling configured: report what the route itself allows.
            maxOutput = Math.max(0, (contextWindow == null ? 0 : contextWindow) - count.tokens());
        }

        Map<String, Object> fits = new LinkedHashMap<>();
        fits.put("context_window", contextWindow);
        fits.put("max_output_tokens", maxOutput);
        if (contextWindow != null) {
            fits.put("fits", count.tokens() + maxOutput <= contextWindow);
            fits.put("remaining_input_tokens", Math.max(0, contextWindow - maxOutput - count.tokens()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "token_count");
        out.put("model", spec.publicModel());
        out.put("upstream_model", upstreamModel);
        out.put("provider", first.providerSlug());
        out.put("prompt_tokens", count.tokens());
        out.put("estimated", count.estimated());
        out.put("fits", fits);
        return out;
    }
}
