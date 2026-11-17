package com.anindra.path.api;

import com.anindra.path.config.AuthFilter;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.gateway.ApiKeyService;
import com.anindra.path.gateway.ChatRequestSpec;
import com.anindra.path.gateway.ChatService;
import com.anindra.path.gateway.ConcurrencyLimiter;
import com.anindra.path.gateway.IdempotencyService;
import com.anindra.path.gateway.ModelCatalogService;
import com.anindra.path.gateway.RouteResolver;
import com.anindra.path.gateway.StreamService;
import com.anindra.path.gateway.UsageService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI-compatible public surface. */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "OpenAI-compatible chat, catalogue and usage")
public class ChatCompletionsController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final ChatService chatService;
    private final StreamService streamService;
    private final com.anindra.path.gateway.EmbeddingsService embeddings;
    private final com.anindra.path.gateway.TokenCountService tokenCount;
    private final ModelCatalogService catalog;
    private final UsageService usageService;
    private final ApiKeyService apiKeys;
    private final IdempotencyService idempotency;
    private final ConcurrencyLimiter concurrency;
    private final com.anindra.path.gateway.DrainState drain;
    private final GatewayProperties props;
    private final com.anindra.path.admin.PromptLogService promptLogs;
    private final Tracer tracer;

    @Operation(summary = "Create a chat completion", description = "Streaming when stream=true")
    @PostMapping("/chat/completions")
    public ResponseEntity<?> chatCompletions(
            @RequestBody ObjectNode body,
            @RequestAttribute(name = AuthFilter.CLIENT_KEY_ATTR, required = false) ClientKey key,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        if (key == null) {
            throw ApiException.unauthorized(
                    "Missing API key. Send it as 'Authorization: Bearer sk-gw-...'.", "missing_api_key");
        }

        drain.begin();
        try {
            int limit = key.getMaxConcurrentRequests() != null
                    ? key.getMaxConcurrentRequests() : props.getDefaultMaxConcurrentRequests();
            Integer projectLimit = (Integer) request.getAttribute(AuthFilter.PROJECT_CONCURRENCY_ATTR);
            if (!concurrency.tryAcquire(key, limit, projectLimit)) {
                throw ApiException.tooManyConcurrent(
                        projectLimit != null && projectLimit > 0 ? projectLimit : limit);
            }
            try {
                Span span = tracer.nextSpan().name("chat.completions").start();
                try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
                    span.tag("gen_ai.request.model", body.path("model").asText("unknown"));
                    span.tag("gen_ai.request.stream", String.valueOf(body.path("stream").asBoolean(false)));
                    return handle(body, key, request, response);
                } finally {
                    span.end();
                }
            } finally {
                concurrency.release(key);
            }
        } finally {
            drain.end();
        }
    }

    private ResponseEntity<?> handle(ObjectNode body, ClientKey key, HttpServletRequest request,
                                     HttpServletResponse response) throws IOException {
        ChatRequestSpec spec = ChatRequestSpec.parse(body);
        String idempotencyKey = normalizeIdempotencyKey(request.getHeader(IDEMPOTENCY_HEADER));
        String requestHash = idempotencyKey == null ? null : ApiKeyService.hash(body.toString());

        if (idempotencyKey != null) {
            var replay = idempotency.begin(key.getId(), idempotencyKey, requestHash);
            if (replay.isPresent()) {
                IdempotencyService.Replay stored = replay.get();
                return ResponseEntity.status(stored.httpStatus())
                        .contentType(MediaType.parseMediaType(stored.contentType()))
                        .header("X-Idempotent-Replay", "true")
                        .body(stored.body());
            }
        }

        if (spec.stream()) {
            return streamCompletion(spec, key, idempotencyKey, requestHash, body.toString(), response);
        }

        ChatService.Result result;
        try {
            result = chatService.complete(spec, key);
        } catch (RuntimeException e) {
            abandon(idempotencyKey, key);
            throw e;
        }

        if (idempotencyKey != null) {
            idempotency.complete(key.getId(), idempotencyKey, 200,
                    MediaType.APPLICATION_JSON_VALUE, result.body().toString());
        }
        if (promptLogs.enabled()) {
            promptLogs.record(key.getId(), org.slf4j.MDC.get("requestId"), spec.publicModel(),
                    body.toString(), result.body().toString());
        }

        return ResponseEntity.ok()
                .header("X-Gateway-Provider", result.providerSlug())
                .header("X-Gateway-Upstream-Model", result.upstreamModel())
                .header("X-Gateway-Cost-Usd", result.costUsd().toPlainString())
                .header("X-Gateway-Latency-Ms", String.valueOf(result.latencyMs()))
                .header("X-Path-Cache", result.cacheState())
                .body(result.body());
    }

    private ResponseEntity<?> streamCompletion(ChatRequestSpec spec, ClientKey key, String idempotencyKey,
                                               String requestHash, String requestBody,
                                               HttpServletResponse response)
            throws IOException {

        StreamService.Prepared prepared;
        try {
            prepared = streamService.prepare(spec, key);
        } catch (RuntimeException e) {
            abandon(idempotencyKey, key);
            throw e;
        }

        try {
            List<RouteResolver.ResolvedRoute> chain = prepared.chain();
            boolean capture = idempotencyKey != null || promptLogs.enabled();
            if (!capture) {
                streamService.stream(response, spec, key, chain);
                return null;
            }

            CapturingResponseWrapper wrapper =
                    new CapturingResponseWrapper(response, props.getIdempotency().getMaxResponseBytes());
            boolean completed;
            try {
                completed = streamService.stream(wrapper, spec, key, chain);
            } catch (RuntimeException e) {
                abandon(idempotencyKey, key);
                throw e;
            }

            if (completed && !wrapper.overflowed()) {
                if (idempotencyKey != null) {
                    idempotency.complete(key.getId(), idempotencyKey, 200,
                            "text/event-stream", wrapper.body());
                }
                if (promptLogs.enabled()) {
                    promptLogs.record(key.getId(), org.slf4j.MDC.get("requestId"), spec.publicModel(),
                            requestBody, wrapper.body());
                }
            } else {
                // Not replayable: release the claim so a retry can run.
                abandon(idempotencyKey, key);
            }
            return null;
        } finally {
            apiKeys.release(key.getId(), prepared.reserved());
        }
    }

    private void abandon(String idempotencyKey, ClientKey key) {
        if (idempotencyKey != null) {
            idempotency.abandon(key.getId(), idempotencyKey);
        }
    }

    private static String normalizeIdempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > 255) {
            throw ApiException.badRequest(
                    "Idempotency-Key must be at most 255 characters.", IDEMPOTENCY_HEADER);
        }
        return value;
    }

    @Operation(summary = "Create embeddings", description = "OpenAI-compatible providers only")
    @PostMapping("/embeddings")
    public ResponseEntity<?> embeddings(
            @RequestBody ObjectNode body,
            @RequestAttribute(name = AuthFilter.CLIENT_KEY_ATTR, required = false) ClientKey key,
            HttpServletRequest request) {
        if (key == null) {
            throw ApiException.unauthorized(
                    "Missing API key. Send it as 'Authorization: Bearer sk-gw-...'.", "missing_api_key");
        }

        String idempotencyKey = normalizeIdempotencyKey(request.getHeader(IDEMPOTENCY_HEADER));
        String requestHash = idempotencyKey == null ? null : ApiKeyService.hash(body.toString());
        if (idempotencyKey != null) {
            var replay = idempotency.begin(key.getId(), idempotencyKey, requestHash);
            if (replay.isPresent()) {
                IdempotencyService.Replay stored = replay.get();
                return ResponseEntity.status(stored.httpStatus())
                        .contentType(MediaType.parseMediaType(stored.contentType()))
                        .header("X-Idempotent-Replay", "true")
                        .body(stored.body());
            }
        }

        drain.begin();
        try {
            int limit = key.getMaxConcurrentRequests() != null
                    ? key.getMaxConcurrentRequests() : props.getDefaultMaxConcurrentRequests();
            Integer projectLimit = (Integer) request.getAttribute(AuthFilter.PROJECT_CONCURRENCY_ATTR);
            if (!concurrency.tryAcquire(key, limit, projectLimit)) {
                throw ApiException.tooManyConcurrent(
                        projectLimit != null && projectLimit > 0 ? projectLimit : limit);
            }
            try {
                var result = embeddings.embed(body, key);
                if (idempotencyKey != null) {
                    idempotency.complete(key.getId(), idempotencyKey, 200,
                            MediaType.APPLICATION_JSON_VALUE, result.body().toString());
                }
                return ResponseEntity.ok()
                        .header("X-Gateway-Provider", result.providerSlug())
                        .header("X-Gateway-Upstream-Model", result.upstreamModel())
                        .header("X-Gateway-Cost-Usd", result.costUsd().toPlainString())
                        .body(result.body());
            } catch (RuntimeException e) {
                abandon(idempotencyKey, key);
                throw e;
            } finally {
                concurrency.release(key);
            }
        } finally {
            drain.end();
        }
    }

    @Operation(summary = "Count prompt tokens for a model",
            description = "Free, authenticated; respects the model allowlist and rate limits")
    @PostMapping("/tokens/count")
    public Map<String, Object> tokenCount(
            @RequestBody ObjectNode body,
            @RequestAttribute(name = AuthFilter.CLIENT_KEY_ATTR, required = false) ClientKey key,
            HttpServletRequest request) {
        if (key == null) {
            throw ApiException.unauthorized(
                    "Missing API key. Send it as 'Authorization: Bearer sk-gw-...'.", "missing_api_key");
        }
        drain.begin();
        try {
            int limit = key.getMaxConcurrentRequests() != null
                    ? key.getMaxConcurrentRequests() : props.getDefaultMaxConcurrentRequests();
            Integer projectLimit = (Integer) request.getAttribute(AuthFilter.PROJECT_CONCURRENCY_ATTR);
            if (!concurrency.tryAcquire(key, limit, projectLimit)) {
                throw ApiException.tooManyConcurrent(
                        projectLimit != null && projectLimit > 0 ? projectLimit : limit);
            }
            try {
                return tokenCount.count(body, key);
            } finally {
                concurrency.release(key);
            }
        } finally {
            drain.end();
        }
    }

    @Operation(summary = "List the model catalogue")
    @GetMapping("/models")
    public Map<String, Object> models() {
        return catalog.list();
    }

    @GetMapping({"/models/{*modelId}"})
    public Map<String, Object> model(@PathVariable String modelId) {
        String id = modelId.startsWith("/") ? modelId.substring(1) : modelId;
        return catalog.find(id).orElseThrow(() ->
                ApiException.notFound("The model '" + id + "' does not exist.", "model_not_found"));
    }

    @Operation(summary = "Token, cost and cache usage for the calling key")
    @GetMapping("/usage")
    public Map<String, Object> usage(
            @RequestAttribute(name = AuthFilter.CLIENT_KEY_ATTR, required = false) ClientKey key,
            @RequestParam(defaultValue = "24h") String window) {
        requireKey(key);
        Duration parsed = Durations.cap(Durations.parse(window, Duration.ofHours(24)), Duration.ofDays(365));
        return usageService.keySummary(key, parsed);
    }

    @GetMapping("/credits")
    public Map<String, Object> credits(
            @RequestAttribute(name = AuthFilter.CLIENT_KEY_ATTR, required = false) ClientKey key) {
        requireKey(key);
        BigDecimal remaining = apiKeys.remaining(key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "credits");
        out.put("key_id", key.getId());
        out.put("key_name", key.getName());
        out.put("currency", "USD");
        out.put("credit_limit_usd", key.getCreditLimitUsd());
        out.put("used_usd", key.getUsedUsd());
        out.put("remaining_usd", remaining);
        out.put("unlimited", remaining == null);
        return out;
    }

    private static void requireKey(ClientKey key) {
        if (key == null) {
            throw ApiException.unauthorized(
                    "Missing API key. Send it as 'Authorization: Bearer sk-gw-...'.", "missing_api_key");
        }
    }
}
