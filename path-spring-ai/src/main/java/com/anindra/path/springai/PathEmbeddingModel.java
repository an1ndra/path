package com.anindra.path.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A Spring AI {@link EmbeddingModel} over the gateway's {@code /v1/embeddings} endpoint.
 *
 * <pre>{@code
 * @Bean
 * EmbeddingModel embeddingModel() {
 *     return PathEmbeddingModel.builder()
 *             .baseUrl("http://path:8080")
 *             .apiKey(System.getenv("PATH_KEY"))
 *             .model("text-embedding-3-small")
 *             .build();
 * }
 * }</pre>
 *
 * <p>The gateway currently serves embeddings for OpenAI-shaped providers only; anything
 * else fails with the gateway's own error, which surfaces as a {@link PathException}.
 */
public final class PathEmbeddingModel implements EmbeddingModel {

    public static final String DEFAULT_MODEL = "text-embedding-3-small";
    private static final int DEFAULT_DIMENSIONS = 1536;

    private final PathHttp http;
    private final String model;
    private final int dimensions;

    private PathEmbeddingModel(PathHttp http, String model, int dimensions) {
        this.http = http;
        this.model = model;
        this.dimensions = dimensions;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseUrl = "http://localhost:8080";
        private String apiKey;
        private String model = DEFAULT_MODEL;
        private boolean idempotency;
        private Duration timeout = Duration.ofSeconds(60);
        private int dimensions = DEFAULT_DIMENSIONS;
        private ObjectMapper mapper = new ObjectMapper();

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder idempotency(boolean idempotency) {
            this.idempotency = idempotency;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Reported by {@link #dimensions()}; the gateway does not return it. */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        public Builder objectMapper(ObjectMapper mapper) {
            this.mapper = mapper;
            return this;
        }

        public PathEmbeddingModel build() {
            return new PathEmbeddingModel(new PathHttp(baseUrl, apiKey, idempotency, timeout, mapper),
                    model, dimensions);
        }
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        ObjectMapper mapper = http.mapper();
        ObjectNode body = mapper.createObjectNode();
        String requested = request.getOptions() == null ? null : request.getOptions().getModel();
        body.put("model", requested == null || requested.isBlank() ? model : requested);
        ArrayNode input = body.putArray("input");
        for (String text : request.getInstructions()) {
            input.add(text);
        }
        Integer requestedDimensions = request.getOptions() == null ? null : request.getOptions().getDimensions();
        if (requestedDimensions != null) {
            body.put("dimensions", requestedDimensions);
        }

        HttpResponse<String> response = http.postJson("/v1/embeddings", body);
        PathHttp.requireSuccess(response, response.body());
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new PathException(response.statusCode(), "invalid_response",
                    "The gateway returned a body that is not JSON", e);
        }

        List<Embedding> embeddings = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            JsonNode vector = item.path("embedding");
            float[] values = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                values[i] = (float) vector.get(i).asDouble();
            }
            embeddings.add(new Embedding(values, item.path("index").asInt(embeddings.size())));
        }
        return new EmbeddingResponse(embeddings,
                new EmbeddingResponseMetadata(root.path("model").asText(model),
                        (org.springframework.ai.chat.metadata.Usage) null));
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
