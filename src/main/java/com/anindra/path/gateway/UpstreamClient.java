package com.anindra.path.gateway;

import com.anindra.path.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Component
@Slf4j
public class UpstreamClient {

    public record Response(int status, String body, Map<String, String> headers) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String header(String name) {
            return headers == null ? null : headers.get(name.toLowerCase());
        }
    }

    private final HttpClient http;
    private final GatewayProperties props;
    private final io.micrometer.tracing.Tracer tracer;

    public UpstreamClient(GatewayProperties props, io.micrometer.tracing.Tracer tracer) {
        this.props = props;
        this.tracer = tracer;
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public Response get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = base(url, Duration.ofSeconds(30)).GET();
        headers.forEach(b::header);
        addTraceparent(b);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(resp.statusCode(), resp.body(), flatten(resp));
    }

    public Response postJson(String url, Map<String, String> headers, String body)
            throws IOException, InterruptedException {
        return postJson(url, headers, body, props.getRequestTimeout());
    }

    public Response postJson(String url, Map<String, String> headers, String body, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = base(url, timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(b::header);
        addTraceparent(b);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(resp.statusCode(), resp.body(), flatten(resp));
    }

    /** Opens a streaming POST. Throws {@link UpstreamException} before returning on non-2xx. */
    public LineStream openStream(String url, Map<String, String> headers, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = base(url, props.getRequestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(b::header);
        addTraceparent(b);

        HttpResponse<InputStream> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            String errorBody;
            try (InputStream in = resp.body()) {
                errorBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw mapError(resp.statusCode(), errorBody, flatten(resp));
        }
        return new LineStream(resp.body(), flatten(resp));
    }

    private static Map<String, String> flatten(HttpResponse<?> response) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(), values.get(0));
            }
        });
        return headers;
    }

    /** W3C trace context, so provider-side logs join the same trace. */
    private void addTraceparent(HttpRequest.Builder builder) {
        io.micrometer.tracing.Span span = tracer.currentSpan();
        if (span != null) {
            var context = span.context();
            builder.header("traceparent",
                    "00-" + context.traceId() + "-" + context.spanId() + "-01");
        }
    }

    private HttpRequest.Builder base(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
    }

    public static UpstreamException mapError(int status, String body) {
        return mapError(status, body, Map.of());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper ERROR_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public static UpstreamException mapError(int status, String body, Map<String, String> headers) {
        String message = body;
        String code = "upstream_error";
        try {
            var node = ERROR_MAPPER.readTree(body);
            if (node.has("error")) {
                var err = node.get("error");
                if (err.isTextual()) {
                    message = err.asText();
                } else {
                    if (err.hasNonNull("message")) {
                        message = err.get("message").asText();
                    }
                    if (err.hasNonNull("type")) {
                        code = err.get("type").asText();
                    } else if (err.hasNonNull("code")) {
                        code = err.get("code").asText();
                    }
                }
            }
        } catch (Exception ignored) {
            // keep the raw body
        }
        if (message == null || message.isBlank()) {
            message = "Upstream returned HTTP " + status;
        }
        if (status == 429) {
            code = "upstream_rate_limited";
        }
        return new UpstreamException(status, code, truncate(message, 800), retryAfterSeconds(headers));
    }

    /** Parses Retry-After in seconds. HTTP-date form is treated as 5s. */
    private static int retryAfterSeconds(Map<String, String> headers) {
        if (headers == null) {
            return 0;
        }
        String raw = headers.get("retry-after");
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(3600, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
