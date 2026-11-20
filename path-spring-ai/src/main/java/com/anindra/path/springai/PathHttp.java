package com.anindra.path.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The small amount of HTTP shared by the chat and embedding models.
 *
 * <p>No Spring AI transport and no client SDK: the gateway speaks the OpenAI wire format,
 * and a plain HTTP call keeps unknown fields out of the picture and the dependency surface
 * to Jackson only. This module is the only place that knows about Spring AI; the gateway
 * does not depend on it.
 */
final class PathHttp {

    private final URI base;
    private final String apiKey;
    private final boolean idempotency;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper;

    PathHttp(String baseUrl, String apiKey, boolean idempotency, Duration timeout, ObjectMapper mapper) {
        this.base = URI.create(trimSlash(baseUrl));
        this.apiKey = apiKey;
        this.idempotency = idempotency;
        this.timeout = timeout;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    HttpResponse<String> postJson(String path, ObjectNode body) {
        return send(path, body, HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<java.io.InputStream> postStream(String path, ObjectNode body) {
        return send(path, body, HttpResponse.BodyHandlers.ofInputStream());
    }

    private <T> HttpResponse<T> send(String path, ObjectNode body,
                                     HttpResponse.BodyHandler<T> handler) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        if (idempotency) {
            // One key per logical call. The gateway replays the stored response instead of
            // charging twice, which is what makes a caller-side retry safe.
            request.header("Idempotency-Key", java.util.UUID.randomUUID().toString());
        }
        try {
            return http.send(request.build(), handler);
        } catch (IOException e) {
            throw PathException.network(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw PathException.network(e);
        }
    }

    /** Turns a non-2xx answer into an exception that names the gateway's error code. */
    static void requireSuccess(HttpResponse<?> response, String rawBody) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        String code = null;
        String message = "Path gateway returned HTTP " + status;
        try {
            JsonNode error = new ObjectMapper().readTree(rawBody).path("error");
            if (error.isObject()) {
                code = error.path("code").asText(null);
                String detail = error.path("message").asText(null);
                if (detail != null && !detail.isBlank()) {
                    message = detail;
                }
            }
        } catch (Exception ignored) {
            // A non-JSON error body is still an error; the status is enough.
        }
        throw new PathException(status, code, message, null);
    }
}
