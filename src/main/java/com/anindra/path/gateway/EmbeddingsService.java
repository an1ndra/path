package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /v1/embeddings for OpenAI-compatible providers. Routes through the same chain
 * resolution as chat, so aliases, weights and fallback order all apply.
 */
@Service
@RequiredArgsConstructor
public class EmbeddingsService {

    public record Result(JsonNode body, String providerSlug, String upstreamModel, BigDecimal costUsd) {
    }

    private final RouteResolver resolver;
    private final ProviderCredentials credentials;
    private final UpstreamClient upstream;
    private final UsageService usage;
    private final CreditGuard creditGuard;
    private final ObjectMapper mapper;

    public Result embed(ObjectNode request, ClientKey key) {
        JsonNode input = request.get("input");
        if (input == null || input.isNull()) {
            throw ApiException.badRequest("'input' is required.", "input");
        }
        String model = request.path("model").asText("");
        if (model.isBlank()) {
            throw ApiException.badRequest("'model' is required.", "model");
        }

        ChatRequestSpec spec = routingSpec(model);
        List<RouteResolver.ResolvedRoute> chain = resolver.resolve(spec, key.getId());
        creditGuard.check(key, spec, chain.get(0));

        RouteResolver.ResolvedRoute resolved = chain.stream()
                .filter(r -> r.provider().getType() == ProviderType.OPENAI)
                .findFirst()
                .orElseThrow(() -> new ApiException(501, "invalid_request_error", "unsupported_endpoint",
                        "Embeddings are only supported for OpenAI-compatible providers."));

        Provider provider = resolved.provider();
        String apiKey = credentials.apiKey(provider);
        if (apiKey == null) {
            throw ApiException.upstream(502,
                    "Provider '" + provider.getSlug() + "' has no API key configured.",
                    "provider_not_configured");
        }

        ObjectNode body = request.deepCopy();
        body.put("model", resolved.route().getUpstreamModelId());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);

        long started = System.nanoTime();
        try {
            UpstreamClient.Response response = upstream.postJson(
                    provider.getBaseUrl() + "/embeddings", headers, body.toString());
            if (!response.ok()) {
                throw ChatService.toApiException(UpstreamClient.mapError(
                        response.status(), response.body(), response.headers()));
            }
            JsonNode parsed = mapper.readTree(response.body());
            if (parsed instanceof ObjectNode object) {
                object.put("model", model);
            }
            int prompt = parsed.path("usage").path("prompt_tokens").asInt(0);
            BigDecimal cost = usage.recordSuccess(key, spec, resolved, TokenUsage.of(prompt, 0),
                    UsageService.CallMeta.nonStream(ChatService.elapsedMs(started), null, "stop", 1, null));
            return new Result(parsed, provider.getSlug(), resolved.route().getUpstreamModelId(), cost);
        } catch (IOException e) {
            throw ApiException.upstream(502, "Could not reach " + provider.getSlug() + ": " + e.getMessage(),
                    "upstream_network_error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.upstream(502, "Request interrupted", "upstream_interrupted");
        }
    }

    /** RouteResolver speaks ChatRequestSpec; embeddings only need the model and fallbacks. */
    private ChatRequestSpec routingSpec(String model) {
        ObjectNode synthetic = mapper.createObjectNode();
        synthetic.put("model", model);
        synthetic.putArray("messages").addObject().put("role", "user").put("content", "");
        return ChatRequestSpec.parse(synthetic);
    }
}
