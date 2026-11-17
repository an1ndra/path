package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pre-flight moderation for OpenAI-shaped providers. Off by default, because it doubles
 * latency and cost for the majority of benign traffic.
 *
 * <p>A flagged request is refused with 400 {@code content_flagged} and the categories;
 * it is never silently dropped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModerationService {

    private final GatewayProperties props;
    private final RouteResolver resolver;
    private final ProviderCredentials credentials;
    private final UpstreamClient upstream;
    private final ObjectMapper mapper;

    public boolean enabled() {
        return props.getGuardrails().isModeration();
    }

    public void check(ChatRequestSpec spec, ClientKey key) {
        if (!enabled()) {
            return;
        }
        String text = TokenCounter.promptText(spec.body());
        if (text.isBlank()) {
            return;
        }

        List<RouteResolver.ResolvedRoute> chain = resolver.resolve(spec, key.getId());
        RouteResolver.ResolvedRoute route = chain.stream()
                .filter(r -> r.provider().getType() == ProviderType.OPENAI)
                .findFirst()
                .orElse(null);
        if (route == null) {
            // Moderation is an OpenAI-shaped surface; providers that lack one are not
            // silently moderated by a different provider's key.
            return;
        }

        Provider provider = route.provider();
        String apiKey = credentials.apiKey(provider);
        if (apiKey == null) {
            log.warn("Moderation skipped: provider '{}' has no credential", provider.getSlug());
            return;
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", props.getGuardrails().getModerationModel());
        payload.put("input", text);

        UpstreamClient.Response response;
        try {
            Map<String, String> headers = ProviderUrls.headers(provider, apiKey, false);
            headers.put("Content-Type", "application/json");
            response = upstream.postJson(ProviderUrls.moderation(provider),
                    headers,
                    payload.toString(), Duration.ofMillis(provider.getRequestTimeoutMs() == null
                            ? 5_000 : Math.min(provider.getRequestTimeoutMs(), 10_000)));
        } catch (Exception e) {
            // A moderation outage must not take the gateway down; log and continue.
            log.warn("Moderation call failed for '{}': {}", provider.getSlug(), e.getMessage());
            return;
        }
        if (!response.ok()) {
            log.warn("Moderation for '{}' returned {}", provider.getSlug(), response.status());
            return;
        }

        try {
            JsonNode body = mapper.readTree(response.body());
            JsonNode result = body.path("results").path(0);
            if (!result.path("flagged").asBoolean(false)) {
                return;
            }
            List<String> categories = new ArrayList<>();
            result.path("categories").fields().forEachRemaining(entry -> {
                if (entry.getValue().asBoolean(false)) {
                    categories.add(entry.getKey());
                }
            });
            throw new ApiException(400, "invalid_request_error", "content_flagged",
                    "The request was blocked by moderation (" + String.join(", ", categories)
                            + ").", "messages");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not parse moderation response: {}", e.getMessage());
        }
    }
}
