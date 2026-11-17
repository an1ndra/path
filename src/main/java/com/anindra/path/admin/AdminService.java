package com.anindra.path.admin;

import com.anindra.path.api.ApiException;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.anindra.path.gateway.ApiKeyService;
import com.anindra.path.gateway.ProviderCredentials;
import com.anindra.path.gateway.ProviderCircuitBreaker;
import com.anindra.path.gateway.UsageService;
import com.anindra.path.repo.ClientKeyRepository;
import com.anindra.path.repo.ModelRouteRepository;
import com.anindra.path.repo.ProviderRepository;
import com.anindra.path.secret.SecretCipher;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminService {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProviderInput(String slug, @JsonAlias("displayName") String displayName, String type,
                                @JsonAlias("baseUrl") String baseUrl, @JsonAlias("apiKey") String apiKey,
                                @JsonAlias("requestTimeoutMs") Integer requestTimeoutMs,
                                @JsonAlias("streamIdleTimeoutMs") Integer streamIdleTimeoutMs,
                                Boolean enabled) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RouteInput(@JsonAlias("publicModelId") String publicModelId,
                             @JsonAlias("providerId") Long providerId,
                             @JsonAlias("upstreamModelId") String upstreamModelId,
                             @JsonAlias("inputPricePerMillion") BigDecimal inputPricePerMillion,
                             @JsonAlias("outputPricePerMillion") BigDecimal outputPricePerMillion,
                             @JsonAlias("cachedInputPricePerMillion") BigDecimal cachedInputPricePerMillion,
                             @JsonAlias("cacheWritePricePerMillion") BigDecimal cacheWritePricePerMillion,
                             Integer priority,
                             Integer weight,
                             @JsonAlias("cacheEnabled") Boolean cacheEnabled,
                             @JsonAlias("cacheTtlSeconds") Integer cacheTtlSeconds,
                             @JsonAlias("contextLength") Integer contextLength,
                             @JsonAlias("supportsStreaming") Boolean supportsStreaming,
                             @JsonAlias("supportsTools") Boolean supportsTools,
                             Boolean enabled, String description) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record KeyInput(String name, @JsonAlias("creditLimitUsd") BigDecimal creditLimitUsd,
                           @JsonAlias("dailyLimitUsd") BigDecimal dailyLimitUsd,
                           @JsonAlias("monthlyLimitUsd") BigDecimal monthlyLimitUsd,
                           @JsonAlias("maxOutputTokens") Integer maxOutputTokens,
                           @JsonAlias("rpmLimit") Integer rpmLimit,
                           @JsonAlias("maxConcurrentRequests") Integer maxConcurrentRequests,
                           Boolean enabled) {
    }

    private final ProviderRepository providers;
    private final ModelRouteRepository routes;
    private final ClientKeyRepository clientKeys;
    private final SecretCipher cipher;
    private final ProviderCredentials credentials;
    private final ApiKeyService apiKeys;
    private final UsageService usage;
    private final ProviderCircuitBreaker breaker;

    // -------------------------------------------------------------- providers

    public List<Map<String, Object>> listProviders() {
        List<Map<String, Object>> out = new ArrayList<>();
        providers.findAllByOrderBySlugAsc().forEach(p -> out.add(providerView(p)));
        return out;
    }

    @Transactional
    public Map<String, Object> createProvider(ProviderInput in) {
        if (in.slug() == null || in.slug().isBlank()) {
            throw ApiException.badRequest("slug is required", "slug");
        }
        if (providers.findBySlug(in.slug()).isPresent()) {
            throw new ApiException(409, "invalid_request_error", "provider_exists",
                    "A provider with slug '" + in.slug() + "' already exists.");
        }
        Provider p = new Provider();
        p.setSlug(in.slug().trim().toLowerCase());
        p.setDisplayName(in.displayName() == null || in.displayName().isBlank()
                ? in.slug().trim() : in.displayName().trim());
        p.setType(parseType(in.type()));
        p.setBaseUrl(requireBaseUrl(in.baseUrl()));
        p.setEnabled(in.enabled() == null || in.enabled());
        p.setRequestTimeoutMs(in.requestTimeoutMs());
        p.setStreamIdleTimeoutMs(in.streamIdleTimeoutMs());
        if (in.apiKey() != null && !in.apiKey().isBlank()) {
            p.setApiKeyCipher(cipher.encrypt(in.apiKey().trim()));
        }
        providers.save(p);
        return providerView(p);
    }

    @Transactional
    public Map<String, Object> updateProvider(Long id, ProviderInput in) {
        Provider p = providers.findById(id)
                .orElseThrow(() -> ApiException.notFound("No provider with id " + id, "provider_not_found"));
        if (in.displayName() != null && !in.displayName().isBlank()) {
            p.setDisplayName(in.displayName().trim());
        }
        if (in.type() != null && !in.type().isBlank()) {
            p.setType(parseType(in.type()));
        }
        if (in.baseUrl() != null && !in.baseUrl().isBlank()) {
            p.setBaseUrl(requireBaseUrl(in.baseUrl()));
        }
        if (in.enabled() != null) {
            p.setEnabled(in.enabled());
        }
        if (in.requestTimeoutMs() != null) {
            p.setRequestTimeoutMs(in.requestTimeoutMs());
        }
        if (in.streamIdleTimeoutMs() != null) {
            p.setStreamIdleTimeoutMs(in.streamIdleTimeoutMs());
        }
        if (in.apiKey() != null) {
            p.setApiKeyCipher(in.apiKey().isBlank() ? null : cipher.encrypt(in.apiKey().trim()));
        }
        providers.save(p);
        return providerView(p);
    }

    @Transactional
    public void deleteProvider(Long id) {
        if (!providers.existsById(id)) {
            throw ApiException.notFound("No provider with id " + id, "provider_not_found");
        }
        routes.deleteByProviderId(id);
        providers.deleteById(id);
    }

    private Map<String, Object> providerView(Provider p) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", p.getId());
        view.put("slug", p.getSlug());
        view.put("display_name", p.getDisplayName());
        view.put("type", p.getType().name());
        view.put("base_url", p.getBaseUrl());
        view.put("enabled", p.isEnabled());
        view.put("credential_source", credentials.source(p));
        view.put("credential_configured", credentials.apiKey(p) != null);
        view.put("credential_hint", credentials.hint(p));
        view.put("request_timeout_ms", p.getRequestTimeoutMs());
        view.put("stream_idle_timeout_ms", p.getStreamIdleTimeoutMs());
        view.put("circuit_state", breaker.state(p.getId()).name().toLowerCase());
        view.put("circuit_retry_after_seconds", breaker.retryAfterSeconds(p.getId()));
        view.put("created_at", p.getCreatedAt().toString());
        view.put("route_count", routes.findByProviderId(p.getId()).size());
        return view;
    }

    private static ProviderType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ProviderType.OPENAI;
        }
        try {
            return ProviderType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("type must be OPENAI or ANTHROPIC", "type");
        }
    }

    private static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw ApiException.badRequest("baseUrl is required", "baseUrl");
        }
        String trimmed = baseUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw ApiException.badRequest("baseUrl must start with http:// or https://", "baseUrl");
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    // ----------------------------------------------------------------- routes

    public List<Map<String, Object>> listRoutes(String publicModelId) {
        List<ModelRoute> found = publicModelId == null || publicModelId.isBlank()
                ? routes.findByEnabledTrueOrderByPublicModelIdAscPriorityAsc()
                : routes.findByPublicModelIdOrderByPriorityAsc(publicModelId);
        Map<Long, String> slugs = new LinkedHashMap<>();
        providers.findAll().forEach(p -> slugs.put(p.getId(), p.getSlug()));
        List<Map<String, Object>> out = new ArrayList<>();
        found.forEach(r -> out.add(routeView(r, slugs.get(r.getProviderId()))));
        return out;
    }

    @Transactional
    public Map<String, Object> createRoute(RouteInput in) {
        if (in.publicModelId() == null || in.publicModelId().isBlank()) {
            throw ApiException.badRequest("publicModelId is required", "publicModelId");
        }
        if (in.providerId() == null) {
            throw ApiException.badRequest("providerId is required", "providerId");
        }
        if (in.upstreamModelId() == null || in.upstreamModelId().isBlank()) {
            throw ApiException.badRequest("upstreamModelId is required", "upstreamModelId");
        }
        Provider provider = providers.findById(in.providerId())
                .orElseThrow(() -> ApiException.notFound(
                        "No provider with id " + in.providerId(), "provider_not_found"));

        ModelRoute route = new ModelRoute(in.publicModelId().trim(), provider.getId(),
                in.upstreamModelId().trim(), in.inputPricePerMillion(), in.outputPricePerMillion(),
                in.priority() == null ? 10 : in.priority());
        route.setCachedInputPricePerMillion(in.cachedInputPricePerMillion());
        route.setCacheWritePricePerMillion(in.cacheWritePricePerMillion());
        applyOptionals(route, in);
        try {
            routes.save(route);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(409, "invalid_request_error", "route_exists",
                    "That public model / provider / upstream model route already exists.");
        }
        return routeView(route, provider.getSlug());
    }

    @Transactional
    public Map<String, Object> updateRoute(Long id, RouteInput in) {
        ModelRoute route = routes.findById(id)
                .orElseThrow(() -> ApiException.notFound("No route with id " + id, "route_not_found"));
        if (in.publicModelId() != null && !in.publicModelId().isBlank()) {
            route.setPublicModelId(in.publicModelId().trim());
        }
        if (in.upstreamModelId() != null && !in.upstreamModelId().isBlank()) {
            route.setUpstreamModelId(in.upstreamModelId().trim());
        }
        if (in.providerId() != null) {
            if (!providers.existsById(in.providerId())) {
                throw ApiException.notFound("No provider with id " + in.providerId(), "provider_not_found");
            }
            route.setProviderId(in.providerId());
        }
        if (in.inputPricePerMillion() != null) {
            route.setInputPricePerMillion(in.inputPricePerMillion());
        }
        if (in.outputPricePerMillion() != null) {
            route.setOutputPricePerMillion(in.outputPricePerMillion());
        }
        if (in.cachedInputPricePerMillion() != null) {
            route.setCachedInputPricePerMillion(in.cachedInputPricePerMillion());
        }
        if (in.cacheWritePricePerMillion() != null) {
            route.setCacheWritePricePerMillion(in.cacheWritePricePerMillion());
        }
        applyOptionals(route, in);
        routes.save(route);
        String slug = providers.findById(route.getProviderId()).map(Provider::getSlug).orElse(null);
        return routeView(route, slug);
    }

    private static void applyOptionals(ModelRoute route, RouteInput in) {
        if (in.priority() != null) {
            route.setPriority(in.priority());
        }
        if (in.weight() != null) {
            route.setWeight(in.weight());
        }
        if (in.cacheEnabled() != null) {
            route.setCacheEnabled(in.cacheEnabled());
        }
        if (in.cacheTtlSeconds() != null) {
            route.setCacheTtlSeconds(in.cacheTtlSeconds());
        }
        if (in.contextLength() != null) {
            route.setContextLength(in.contextLength());
        }
        if (in.supportsStreaming() != null) {
            route.setSupportsStreaming(in.supportsStreaming());
        }
        if (in.supportsTools() != null) {
            route.setSupportsTools(in.supportsTools());
        }
        if (in.enabled() != null) {
            route.setEnabled(in.enabled());
        }
        if (in.description() != null) {
            route.setDescription(in.description());
        }
    }

    @Transactional
    public void deleteRoute(Long id) {
        if (!routes.existsById(id)) {
            throw ApiException.notFound("No route with id " + id, "route_not_found");
        }
        routes.deleteById(id);
    }

    private Map<String, Object> routeView(ModelRoute r, String providerSlug) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", r.getId());
        view.put("public_model_id", r.getPublicModelId());
        view.put("provider_id", r.getProviderId());
        view.put("provider", providerSlug);
        view.put("upstream_model_id", r.getUpstreamModelId());
        view.put("input_price_per_million", r.getInputPricePerMillion());
        view.put("output_price_per_million", r.getOutputPricePerMillion());
        view.put("cached_input_price_per_million", r.getCachedInputPricePerMillion());
        view.put("cache_write_price_per_million", r.getCacheWritePricePerMillion());
        view.put("priority", r.getPriority());
        view.put("context_length", r.getContextLength());
        view.put("supports_streaming", r.isSupportsStreaming());
        view.put("supports_tools", r.isSupportsTools());
        view.put("enabled", r.isEnabled());
        view.put("weight", r.getWeight());
        view.put("cache_enabled", r.isCacheEnabled());
        view.put("cache_ttl_seconds", r.getCacheTtlSeconds());
        view.put("description", r.getDescription());
        return view;
    }

    // ------------------------------------------------------------------- keys

    public List<Map<String, Object>> listKeys() {
        List<Map<String, Object>> out = new ArrayList<>();
        clientKeys.findAllByOrderByCreatedAtDesc().forEach(k -> out.add(keyView(k)));
        return out;
    }

    @Transactional
    public Map<String, Object> createKey(KeyInput in) {
        ApiKeyService.CreatedKey created = apiKeys.create(in.name(), in.creditLimitUsd(), in.rpmLimit());
        if (in.maxConcurrentRequests() != null) {
            created.key().setMaxConcurrentRequests(in.maxConcurrentRequests());
        }
        created.key().setDailyLimitUsd(in.dailyLimitUsd());
        created.key().setMonthlyLimitUsd(in.monthlyLimitUsd());
        created.key().setMaxOutputTokens(in.maxOutputTokens());
        clientKeys.save(created.key());
        Map<String, Object> view = keyView(created.key());
        view.put("key", created.plaintext());
        view.put("warning", "Store this key now. It is not shown again.");
        return view;
    }

    @Transactional
    public Map<String, Object> updateKey(Long id, KeyInput in) {
        ClientKey key = clientKeys.findById(id)
                .orElseThrow(() -> ApiException.notFound("No key with id " + id, "key_not_found"));
        if (in.name() != null && !in.name().isBlank()) {
            key.setName(in.name().trim());
        }
        if (in.creditLimitUsd() != null) {
            key.setCreditLimitUsd(in.creditLimitUsd());
        }
        if (in.rpmLimit() != null) {
            if (in.rpmLimit() < 1) {
                throw ApiException.badRequest("rpmLimit must be at least 1", "rpmLimit");
            }
            key.setRpmLimit(in.rpmLimit());
        }
        if (in.maxConcurrentRequests() != null) {
            if (in.maxConcurrentRequests() < 0) {
                throw ApiException.badRequest("maxConcurrentRequests cannot be negative", "maxConcurrentRequests");
            }
            key.setMaxConcurrentRequests(in.maxConcurrentRequests());
        }
        if (in.dailyLimitUsd() != null) {
            key.setDailyLimitUsd(in.dailyLimitUsd());
        }
        if (in.monthlyLimitUsd() != null) {
            key.setMonthlyLimitUsd(in.monthlyLimitUsd());
        }
        if (in.maxOutputTokens() != null) {
            key.setMaxOutputTokens(in.maxOutputTokens());
        }
        if (in.enabled() != null) {
            key.setEnabled(in.enabled());
        }
        clientKeys.save(key);
        return keyView(key);
    }

    @Transactional
    public Map<String, Object> revokeKey(Long id) {
        ClientKey key = clientKeys.findById(id)
                .orElseThrow(() -> ApiException.notFound("No key with id " + id, "key_not_found"));
        key.setEnabled(false);
        key.setRevokedAt(Instant.now());
        clientKeys.save(key);
        return keyView(key);
    }

    @Transactional
    public Map<String, Object> topUpKey(Long id, BigDecimal amountUsd) {
        ClientKey key = clientKeys.findById(id)
                .orElseThrow(() -> ApiException.notFound("No key with id " + id, "key_not_found"));
        if (amountUsd == null || amountUsd.signum() <= 0) {
            throw ApiException.badRequest("amountUsd must be positive", "amountUsd");
        }
        BigDecimal limit = key.getCreditLimitUsd() == null ? BigDecimal.ZERO : key.getCreditLimitUsd();
        key.setCreditLimitUsd(limit.add(amountUsd));
        clientKeys.save(key);
        return keyView(key);
    }

    private Map<String, Object> keyView(ClientKey k) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", k.getId());
        view.put("name", k.getName());
        view.put("key_prefix", k.getKeyPrefix());
        view.put("key_suffix", k.getKeySuffix());
        view.put("masked_key", k.getKeyPrefix() + "..." + k.getKeySuffix());
        view.put("enabled", k.isEnabled());
        view.put("rpm_limit", k.getRpmLimit());
        view.put("max_concurrent_requests", k.getMaxConcurrentRequests());
        view.put("daily_limit_usd", k.getDailyLimitUsd());
        view.put("monthly_limit_usd", k.getMonthlyLimitUsd());
        view.put("max_output_tokens", k.getMaxOutputTokens());
        view.put("credit_limit_usd", k.getCreditLimitUsd());
        view.put("used_usd", k.getUsedUsd());
        view.put("created_at", k.getCreatedAt().toString());
        view.put("last_used_at", k.getLastUsedAt() == null ? null : k.getLastUsedAt().toString());
        view.put("revoked_at", k.getRevokedAt() == null ? null : k.getRevokedAt().toString());
        return view;
    }

    // ------------------------------------------------------------------ stats

    public Map<String, Object> stats(Duration window) {
        Map<String, Object> out = new LinkedHashMap<>(usage.adminStats(window));
        out.put("active_keys", clientKeys.countByEnabledTrue());
        out.put("providers", providers.count());
        out.put("routes", routes.count());
        return out;
    }
}
