package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.anindra.path.api.ApiException;
import com.anindra.path.api.GlobalExceptionHandler;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Server-sent-events chat completions.
 *
 * The route chain and credit check are resolved before a single byte is written, so
 * failures there still produce a normal JSON error with a real HTTP status. Once the
 * stream has started the client is committed to that route; a later failure is
 * delivered as an SSE error event followed by {@code [DONE]}.
 */
@Service
@Slf4j
public class StreamService {

    private final RouteResolver resolver;
    private final ProviderCredentials credentials;
    private final UpstreamClient upstream;
    private final UsageService usage;
    private final CreditGuard creditGuard;
    private final ProviderCircuitBreaker breaker;
    private final ResponseCacheService cache;
    private final ObjectMapper mapper;
    private final GatewayProperties props;
    private final Map<ProviderType, ProviderAdapter> adapters = new EnumMap<>(ProviderType.class);

    public StreamService(RouteResolver resolver, ProviderCredentials credentials, UpstreamClient upstream,
                         UsageService usage, CreditGuard creditGuard, ProviderCircuitBreaker breaker,
                         ResponseCacheService cache, ObjectMapper mapper, GatewayProperties props,
                         List<ProviderAdapter> adapterList) {
        this.resolver = resolver;
        this.credentials = credentials;
        this.upstream = upstream;
        this.usage = usage;
        this.creditGuard = creditGuard;
        this.breaker = breaker;
        this.cache = cache;
        this.mapper = mapper;
        this.props = props;
        adapterList.forEach(a -> adapters.put(a.type(), a));
    }

    /** Resolve the fallback chain and authorise spend. Throws before any bytes are sent. */
    public List<RouteResolver.ResolvedRoute> prepare(ChatRequestSpec spec, ClientKey key) {
        List<RouteResolver.ResolvedRoute> chain = resolver.resolve(spec);
        creditGuard.check(key, spec, chain.get(0));
        return chain;
    }

    /** Blocks for the whole stream. Safe on a virtual thread. */
    public boolean stream(HttpServletResponse response, ChatRequestSpec spec, ClientKey key,
                          List<RouteResolver.ResolvedRoute> chain) throws IOException {
        response.setStatus(200);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        boolean cacheEligible = ResponseCacheService.eligible(spec, chain.get(0).route());
        String cacheKey = cacheEligible
                ? cache.key(key.getId(), spec.publicModel(), spec.body().toString()) : null;
        if (cacheKey != null) {
            var hit = cache.lookup(cacheKey);
            if (hit.isPresent()) {
                response.setHeader("X-Path-Cache", "hit");
                OutputStream cached = response.getOutputStream();
                cached.write(hit.get().body().getBytes(StandardCharsets.UTF_8));
                cached.flush();
                usage.recordCacheHit(key, spec, chain.get(0), hit.get(), true);
                return true;
            }
        }
        response.setHeader("X-Path-Cache", cacheEligible ? "miss" : "off");

        OutputStream raw = response.getOutputStream();
        Tee tee = cacheEligible ? new Tee(raw, props.getCache().getMaxBodyBytes()) : null;
        OutputStream out = tee == null ? raw : tee;
        out.write(": open\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        long started = System.nanoTime();
        ApiException lastError = null;
        List<String> failures = new java.util.ArrayList<>();
        int attempts = 0;
        String fallbackFrom = null;
        Long ttftMs = null;
        String requestId = null;
        String finishReason = null;
        int generatedChars = 0;
        long available = chain.stream().filter(r -> breaker.isAvailable(r.provider().getId())).count();
        int maxAttempts = Math.min(chain.size(), Math.max(1, props.getBreaker().getMaxAttempts()));

        for (int i = 0; i < chain.size() && attempts < maxAttempts; i++) {
            RouteResolver.ResolvedRoute rr = chain.get(i);
            Provider provider = rr.provider();
            ProviderAdapter adapter = adapters.get(provider.getType());
            Long providerId = provider.getId();

            if (available > 0 && !breaker.isAvailable(providerId)) {
                lastError = new ApiException(503, "upstream_error", "circuit_open",
                        "Provider '" + provider.getSlug() + "' is temporarily unavailable.");
                failures.add(provider.getSlug() + ": circuit open");
                fallbackFrom = fallbackFrom == null ? provider.getSlug() : fallbackFrom;
                continue;
            }

            attempts++;

            if (adapter == null) {
                lastError = ApiException.upstream(500, "No adapter for " + provider.getType(), "adapter_missing");
                continue;
            }
            String apiKey = credentials.apiKey(provider);
            if (apiKey == null) {
                lastError = ApiException.upstream(502,
                        "Provider '" + provider.getSlug() + "' has no API key configured.",
                        "provider_not_configured");
                failures.add(provider.getSlug() + ": not configured");
                fallbackFrom = fallbackFrom == null ? provider.getSlug() : fallbackFrom;
                continue;
            }

            LineStream stream = null;
            boolean committed = false;

            try {
                UpstreamRequest request = adapter.build(spec, rr.route(), provider, apiKey, true);
                stream = upstream.openStream(request.url(), request.headers(), request.body());
                StreamTranslator translator = adapter.newStreamTranslator(spec, rr.route());

                if (requestId == null) {
                    requestId = ChatService.firstNonBlank(
                            stream.headers().get("x-request-id"),
                            stream.headers().get("request-id"),
                            stream.headers().get("openai-request-id"));
                }

                while (true) {
                    String line = stream.poll(props.getStreamIdleTimeout());
                    if (line == null) {
                        break;
                    }
                    for (JsonNode chunk : translator.translate(line)) {
                        if (ttftMs == null) {
                            ttftMs = ChatService.elapsedMs(started);
                        }
                        if (requestId == null) {
                            requestId = chunk.path("id").asText(null);
                        }
                        for (JsonNode choice : chunk.path("choices")) {
                            String reason = choice.path("finish_reason").asText(null);
                            if (reason != null) {
                                finishReason = reason;
                            }
                            JsonNode content = choice.path("delta").path("content");
                            if (content.isTextual()) {
                                generatedChars += content.asText().length();
                            }
                        }
                        send(out, mapper.writeValueAsString(chunk));
                        committed = true;
                    }
                    if (translator.done()) {
                        break;
                    }
                }

                TokenUsage tokens = translator.usage();
                if (!tokens.reported()) {
                    tokens = TokenUsage.estimated(
                            spec.estimatePromptTokens(rr.route().getUpstreamModelId()),
                            Math.max(1, generatedChars / 4));
                }
                if (spec.includeUsage() && tokens.reported()) {
                    send(out, usageChunk(spec, tokens));
                }
                send(out, "[DONE]");

                long latency = ChatService.elapsedMs(started);
                var cost = usage.recordSuccess(key, spec, rr, tokens,
                        new UsageService.CallMeta(latency, ttftMs, true, requestId, finishReason,
                                attempts, fallbackFrom));
                log.info("stream ok model={} provider={} tokens={} cached={} cost={} ttft={}ms latency={}ms",
                        spec.publicModel(), provider.getSlug(), tokens.total(), tokens.cached(),
                        cost.toPlainString(), ttftMs, latency);
                breaker.onSuccess(providerId);
                if (tee != null && cacheKey != null && !tee.overflowed()) {
                    cache.store(cacheKey, key.getId(), spec.publicModel(), provider.getSlug(),
                            rr.route(), "text/event-stream", tee.body(), tokens, cost);
                }
                return true;

            } catch (UpstreamException e) {
                if (e.retryable()) {
                    if (e.status() == 429 && e.retryAfterSeconds() > 0) {
                        breaker.onRateLimited(providerId, java.time.Duration.ofSeconds(e.retryAfterSeconds()));
                    } else {
                        breaker.onFailure(providerId);
                    }
                }
                lastError = ChatService.toApiException(e);
                if (!committed && e.retryable() && i < chain.size() - 1) {
                    log.warn("Stream route {} failed before first chunk ({}), trying next",
                            provider.getSlug(), e.getMessage());
                    failures.add(provider.getSlug() + ": " + e.getMessage());
                    fallbackFrom = fallbackFrom == null ? provider.getSlug() : fallbackFrom;
                    continue;
                }
                usage.recordError(key, spec, provider.getSlug(), rr.route().getUpstreamModelId(),
                        ChatService.elapsedMs(started), true, attempts, lastError.code(), lastError.getMessage());
                emitError(out, lastError);
                return false;

            } catch (TimeoutException e) {
                breaker.onFailure(providerId);
                lastError = new ApiException(504, "upstream_error", "upstream_timeout",
                        "The upstream sent nothing for " + props.getStreamIdleTimeout().toSeconds() + "s.");
                if (!committed && i < chain.size() - 1) {
                    failures.add(provider.getSlug() + ": idle timeout");
                    fallbackFrom = fallbackFrom == null ? provider.getSlug() : fallbackFrom;
                    continue;
                }
                usage.recordError(key, spec, provider.getSlug(), rr.route().getUpstreamModelId(),
                        ChatService.elapsedMs(started), true, attempts, lastError.code(), lastError.getMessage());
                emitError(out, lastError);
                return false;

            } catch (IOException e) {
                breaker.onFailure(providerId);
                if (!committed) {
                    lastError = ApiException.upstream(502,
                            "Could not reach " + provider.getSlug() + ": " + e.getMessage(),
                            "upstream_network_error");
                    if (i < chain.size() - 1) {
                        failures.add(provider.getSlug() + ": " + e.getMessage());
                        fallbackFrom = fallbackFrom == null ? provider.getSlug() : fallbackFrom;
                        continue;
                    }
                }
                lastError = ApiException.upstream(502,
                        "Stream to " + provider.getSlug() + " broke: " + e.getMessage(),
                        "upstream_stream_error");
                usage.recordError(key, spec, provider.getSlug(), rr.route().getUpstreamModelId(),
                        ChatService.elapsedMs(started), true, attempts, lastError.code(), lastError.getMessage());
                try {
                    emitError(out, lastError);
                } catch (IOException clientGone) {
                    log.debug("Client disconnected after upstream failure");
                }
                return false;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;

            } finally {
                if (stream != null) {
                    stream.close();
                }
            }
        }

        if (lastError == null) {
            lastError = ApiException.upstream(502, "All upstream routes failed.", "all_providers_failed");
        } else if (!failures.isEmpty()) {
            lastError = new ApiException(lastError.status(), lastError.type(), lastError.code(),
                    lastError.getMessage() + " (routes tried: " + String.join("; ", failures) + ")");
        }
        usage.recordError(key, spec, null, null, ChatService.elapsedMs(started), true, attempts,
                lastError.code(), lastError.getMessage());
        emitError(out, lastError);
        return false;
    }

    /** Writes through while capturing the SSE payload for the response cache. */
    private static final class Tee extends OutputStream {

        private final OutputStream delegate;
        private final StringBuilder captured = new StringBuilder();
        private final int maxBytes;
        private boolean overflow;

        private Tee(OutputStream delegate, int maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            append(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            append(b, off, len);
        }

        private void append(byte[] b, int off, int len) {
            if (overflow) {
                return;
            }
            if (captured.length() + len > maxBytes) {
                overflow = true;
                return;
            }
            captured.append(new String(b, off, len, StandardCharsets.UTF_8));
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private boolean overflowed() {
            return overflow;
        }

        private String body() {
            return captured.toString();
        }
    }

    private void send(OutputStream out, String data) throws IOException {
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String usageChunk(ChatRequestSpec spec, TokenUsage tokens) throws IOException {
        ObjectNode chunk = mapper.createObjectNode();
        chunk.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""));
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", Instant.now().getEpochSecond());
        chunk.put("model", spec.publicModel());
        chunk.putArray("choices");
        ObjectNode usage = chunk.putObject("usage");
        usage.put("prompt_tokens", tokens.prompt());
        usage.put("completion_tokens", tokens.completion());
        usage.put("total_tokens", tokens.total());
        if (tokens.cached() > 0) {
            usage.putObject("prompt_tokens_details").put("cached_tokens", tokens.cached());
        }
        if (tokens.reasoning() > 0) {
            usage.putObject("completion_tokens_details").put("reasoning_tokens", tokens.reasoning());
        }
        return mapper.writeValueAsString(chunk);
    }

    private void emitError(OutputStream out, ApiException e) throws IOException {
        Map<String, Object> envelope =
                GlobalExceptionHandler.envelope(e.code(), e.type(), e.getMessage(), e.param());
        send(out, mapper.writeValueAsString(envelope));
        send(out, "[DONE]");
    }
}
