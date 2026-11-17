package com.anindra.path.gateway;

import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.ResponseCacheEntry;
import com.anindra.path.repo.ResponseCacheEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * Exact-match response cache.
 *
 * Only deterministic requests qualify: the route must opt in, the request must set
 * {@code temperature: 0} explicitly, and it must not use tools (tool calls are not
 * side-effect free to replay). Keys are scoped to the client key so one tenant can
 * never read another's cached completion.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResponseCacheService {

    public record Entry(String contentType, String body, String providerSlug, BigDecimal costUsd,
                        int promptTokens, int completionTokens, int totalTokens) {
    }

    private final ResponseCacheEntryRepository entries;
    private final GatewayProperties props;

    public static boolean eligible(ChatRequestSpec spec, ModelRoute route) {
        if (!route.isCacheEnabled() || spec.hasTools()) {
            return false;
        }
        JsonNode temperature = spec.body().get("temperature");
        return temperature != null && temperature.isNumber() && temperature.asDouble() == 0.0;
    }

    public String key(Long clientKeyId, String model, String requestBody) {
        return ApiKeyService.hash(clientKeyId + "|" + model + "|" + requestBody);
    }

    public Optional<Entry> lookup(String cacheKey) {
        return entries.findByCacheKey(cacheKey).flatMap(record -> {
            if (record.getExpiresAt().isBefore(Instant.now())) {
                entries.delete(record);
                return Optional.empty();
            }
            return Optional.of(new Entry(record.getContentType(), record.getBody(),
                    record.getProviderSlug(), record.getCostUsd(),
                    record.getPromptTokens(), record.getCompletionTokens(), record.getTotalTokens()));
        });
    }

    @Transactional
    public void store(String cacheKey, Long clientKeyId, String publicModel, String providerSlug,
                      ModelRoute route, String contentType, String body, TokenUsage usage, BigDecimal costUsd) {
        if (body == null || body.isBlank()
                || body.getBytes(StandardCharsets.UTF_8).length > props.getCache().getMaxBodyBytes()) {
            return;
        }
        entries.findByCacheKey(cacheKey).ifPresent(entries::delete);

        ResponseCacheEntry record = new ResponseCacheEntry();
        record.setCacheKey(cacheKey);
        record.setClientKeyId(clientKeyId);
        record.setPublicModelId(publicModel);
        record.setProviderSlug(providerSlug);
        record.setContentType(contentType);
        record.setBody(body);
        record.setPromptTokens(usage == null ? 0 : usage.prompt());
        record.setCompletionTokens(usage == null ? 0 : usage.completion());
        record.setTotalTokens(usage == null ? 0 : usage.total());
        record.setCostUsd(costUsd == null ? BigDecimal.ZERO : costUsd);
        record.setExpiresAt(Instant.now().plusSeconds(Math.max(1, route.getCacheTtlSeconds())));
        entries.save(record);
    }

    @Scheduled(fixedDelay = 1_800_000)
    @Transactional
    void purgeExpired() {
        long removed = entries.deleteByExpiresAtBefore(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired cache entries", removed);
        }
    }
}
