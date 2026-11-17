package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Non-streaming chat completions with route fallback. */
@Service
@Slf4j
public class ChatService {

    public record Result(JsonNode body, String providerSlug, String upstreamModel,
                         BigDecimal costUsd, long latencyMs, String cacheState) {
    }

    private final RouteResolver resolver;
    private final ProviderCredentials credentials;
    private final UpstreamClient upstream;
    private final UsageService usage;
    private final ApiKeyService apiKeys;
    private final CreditGuard creditGuard;
    private final ProviderCircuitBreaker breaker;
    private final ResponseCacheService cache;
    private final GatewayProperties props;
    private final ObjectMapper mapper;
    private final Map<ProviderType, ProviderAdapter> adapters = new EnumMap<>(ProviderType.class);

    public ChatService(RouteResolver resolver, ProviderCredentials credentials, UpstreamClient upstream,
                       UsageService usage, ApiKeyService apiKeys, CreditGuard creditGuard,
                       ProviderCircuitBreaker breaker,
                       ResponseCacheService cache, GatewayProperties props, ObjectMapper mapper,
                       List<ProviderAdapter> adapterList) {
        this.resolver = resolver;
        this.credentials = credentials;
        this.upstream = upstream;
        this.usage = usage;
        this.apiKeys = apiKeys;
        this.creditGuard = creditGuard;
        this.breaker = breaker;
        this.cache = cache;
        this.props = props;
        this.mapper = mapper;
        adapterList.forEach(a -> adapters.put(a.type(), a));
    }

    public Result complete(ChatRequestSpec spec, ClientKey key) {
        List<RouteResolver.ResolvedRoute> chain = resolver.resolve(spec, key.getId());
        BigDecimal reserved = creditGuard.check(key, spec, chain.get(0));
        try {
        List<String> failures = new ArrayList<>();
        ApiException lastError = null;
        long started = System.nanoTime();
        int attempts = 0;
        String fallbackFrom = null;

        boolean cacheEligible = ResponseCacheService.eligible(spec, chain.get(0).route());
        String cacheKey = cacheEligible
                ? cache.key(key.getId(), spec.publicModel(), spec.body().toString()) : null;
        if (cacheKey != null) {
            var hit = cache.lookup(cacheKey);
            if (hit.isPresent()) {
                ResponseCacheService.Entry entry = hit.get();
                usage.recordCacheHit(key, spec, chain.get(0), entry, false);
                try {
                    return new Result(mapper.readTree(entry.body()), entry.providerSlug(),
                            chain.get(0).route().getUpstreamModelId(), BigDecimal.ZERO,
                            elapsedMs(started), "hit");
                } catch (IOException e) {
                    log.warn("Cached body for {} is unreadable; falling through", spec.publicModel());
                }
            }
        }

        long available = chain.stream().filter(r -> breaker.isAvailable(r.provider().getId())).count();
        int maxAttempts = Math.min(chain.size(), Math.max(1, props.getBreaker().getMaxAttempts()));

        for (int i = 0; i < chain.size() && attempts < maxAttempts; i++) {
            RouteResolver.ResolvedRoute rr = chain.get(i);
            ProviderAdapter adapter = adapterFor(rr.provider());
            String apiKey = credentials.apiKey(rr.provider());
            Long providerId = rr.provider().getId();

            if (available > 0 && !breaker.isAvailable(providerId)) {
                lastError = new ApiException(503, "upstream_error", "circuit_open",
                        "Provider '" + rr.providerSlug() + "' is temporarily unavailable.");
                failures.add(rr.providerSlug() + ": circuit open");
                fallbackFrom = fallbackFrom == null ? rr.providerSlug() : fallbackFrom;
                continue;
            }

            attempts++;

            if (apiKey == null) {
                lastError = ApiException.upstream(502,
                        "Provider '" + rr.providerSlug() + "' has no API key configured.",
                        "provider_not_configured");
                failures.add(rr.providerSlug() + ": not configured");
                fallbackFrom = fallbackFrom == null ? rr.providerSlug() : fallbackFrom;
                continue;
            }

            try {
                UpstreamRequest request = adapter.build(spec, rr.route(), rr.provider(), apiKey, false);
                Duration timeout = rr.provider().getRequestTimeoutMs() != null
                        ? Duration.ofMillis(rr.provider().getRequestTimeoutMs())
                        : props.getRequestTimeout();
                UpstreamClient.Response response = upstream.postJson(
                        request.url(), request.headers(), request.body(), timeout);

                if (!response.ok()) {
                    UpstreamException failure = UpstreamClient.mapError(
                            response.status(), response.body(), response.headers());
                    log.warn("Upstream {} -> {}: {}", rr.providerSlug(), failure.status(), failure.getMessage());
                    if (failure.retryable()) {
                        recordBreakerFailure(providerId, failure);
                    }
                    if (failure.retryable() && i < chain.size() - 1) {
                        failures.add(rr.providerSlug() + ": " + failure.getMessage());
                        lastError = toApiException(failure);
                        fallbackFrom = fallbackFrom == null ? rr.providerSlug() : fallbackFrom;
                        continue;
                    }
                    lastError = toApiException(failure);
                    long latency = elapsedMs(started);
                    usage.recordError(key, spec, rr.providerSlug(), rr.route().getUpstreamModelId(),
                            latency, false, attempts, lastError.code(), lastError.getMessage());
                    throw lastError;
                }

                JsonNode upstreamJson = mapper.readTree(response.body());
                JsonNode normalized = adapter.parseFullResponse(upstreamJson, spec, rr.route());

                TokenUsage tokens = adapter.parseUsage(upstreamJson);
                if (!tokens.reported()) {
                    tokens = estimate(spec, normalized, rr.route().getUpstreamModelId());
                }

                String requestId = firstNonBlank(
                        response.header("x-request-id"),
                        response.header("request-id"),
                        response.header("openai-request-id"),
                        normalized.path("id").asText(null));
                String finishReason = normalized.path("choices").path(0).path("finish_reason").asText(null);

                long latency = elapsedMs(started);
                BigDecimal cost = usage.recordSuccess(key, spec, rr, tokens,
                        UsageService.CallMeta.nonStream(latency, requestId, finishReason, attempts, fallbackFrom));
                breaker.onSuccess(providerId);
                if (cacheKey != null) {
                    cache.store(cacheKey, key.getId(), spec.publicModel(), rr.providerSlug(),
                            rr.route(), "application/json", normalized.toString(), tokens, cost);
                }
                return new Result(normalized, rr.providerSlug(), rr.route().getUpstreamModelId(), cost,
                        latency, cacheKey == null ? "off" : "miss");

            } catch (IOException e) {
                breaker.onFailure(providerId);
                lastError = ApiException.upstream(502,
                        "Could not reach " + rr.providerSlug() + ": " + e.getMessage(), "upstream_network_error");
                failures.add(rr.providerSlug() + ": " + e.getMessage());
                fallbackFrom = fallbackFrom == null ? rr.providerSlug() : fallbackFrom;
                if (i == chain.size() - 1) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.upstream(502, "Request interrupted", "upstream_interrupted");
            }
        }

        if (lastError == null) {
            lastError = ApiException.upstream(502, "All upstream routes failed.", "all_providers_failed");
        } else if (!failures.isEmpty()) {
            lastError = new ApiException(lastError.status(), lastError.type(), lastError.code(),
                    lastError.getMessage() + " (routes tried: " + String.join("; ", failures) + ")");
        }
        usage.recordError(key, spec, null, null, elapsedMs(started), false, attempts,
                lastError.code(), lastError.getMessage());
        throw lastError;
        } finally {
            apiKeys.release(key.getId(), reserved);
        }
    }

    /** Used only when a provider returns no usage block at all. */
    private static TokenUsage estimate(ChatRequestSpec spec, JsonNode normalized, String upstreamModel) {
        int prompt = spec.estimatePromptTokens(upstreamModel);
        JsonNode content = normalized.path("choices").path(0).path("message").path("content");
        int completion = content.isTextual() ? Math.max(1, content.asText().length() / 4) : 0;
        return TokenUsage.estimated(prompt, completion);
    }

    private void recordBreakerFailure(Long providerId, UpstreamException failure) {
        if (failure.status() == 429 && failure.retryAfterSeconds() > 0) {
            breaker.onRateLimited(providerId, java.time.Duration.ofSeconds(failure.retryAfterSeconds()));
        } else {
            breaker.onFailure(providerId);
        }
    }

    private ProviderAdapter adapterFor(Provider provider) {
        ProviderAdapter adapter = adapters.get(provider.getType());
        if (adapter == null) {
            throw ApiException.upstream(500, "No adapter for provider type " + provider.getType(),
                    "adapter_missing");
        }
        return adapter;
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static ApiException toApiException(UpstreamException e) {
        int status = e.status();
        if (status == 400 || status == 422) {
            return new ApiException(400, "invalid_request_error", "upstream_invalid_request", e.getMessage());
        }
        if (status == 401 || status == 403) {
            return new ApiException(502, "upstream_error", "provider_auth_error",
                    "Upstream rejected the gateway credential: " + e.getMessage());
        }
        if (status == 404) {
            return ApiException.notFound(e.getMessage(), "model_not_found");
        }
        if (status == 429) {
            return ApiException.rateLimited("Upstream rate limit hit: " + e.getMessage(), 5);
        }
        if (status == 408) {
            return new ApiException(504, "upstream_error", "upstream_timeout", e.getMessage());
        }
        return new ApiException(502, "upstream_error", "upstream_error", e.getMessage());
    }

    static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
