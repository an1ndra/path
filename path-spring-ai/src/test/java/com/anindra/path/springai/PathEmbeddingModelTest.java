package com.anindra.path.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingOptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PathEmbeddingModelTest {

    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"object":"list","model":"text-embedding-3-small",
                     "data":[{"object":"embedding","index":0,"embedding":[0.5,-0.25]}],
                     "usage":{"prompt_tokens":3,"total_tokens":3}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private PathEmbeddingModel model() {
        return PathEmbeddingModel.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("sk-test")
                .model("text-embedding-3-small")
                .dimensions(2)
                .build();
    }

    @Test
    void returnsTheVectorsTheGatewaySent() {
        EmbeddingResponse response = model().call(new EmbeddingRequest(List.of("hello"),
                EmbeddingOptions.builder().model("text-embedding-3-small").build()));

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResult().getOutput()).containsExactly(0.5f, -0.25f);
        assertThat(response.getMetadata().getModel()).isEqualTo("text-embedding-3-small");
    }

    @Test
    void sendsTheInputAndModel() throws Exception {
        model().embed("hello");

        JsonNode sent = mapper.readTree(requestBody.get());
        assertThat(sent.path("model").asText()).isEqualTo("text-embedding-3-small");
        assertThat(sent.path("input").get(0).asText()).isEqualTo("hello");
    }

    @Test
    void reportsTheConfiguredDimensions() {
        assertThat(model().dimensions()).isEqualTo(2);
    }
}
