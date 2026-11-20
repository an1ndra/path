package com.anindra.path.gateway;

import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vertex auth end to end against a stub Google: the service-account JSON signs an RS256
 * assertion, the assertion is exchanged for an access token, and the token is reused.
 */
class VertexTokenServiceTest {

    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private KeyPair keyPair;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> assertion = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String pair : form.split("&")) {
                if (pair.startsWith("assertion=")) {
                    assertion.set(URLDecoder.decode(pair.substring("assertion=".length()),
                            StandardCharsets.UTF_8));
                }
            }
            byte[] body = "{\"access_token\":\"ya29.test\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/projects/demo/locations/us-central1/publishers/google/models/gemini-2.5-pro:generateContent",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}"
                            .getBytes(StandardCharsets.UTF_8);
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

    private String serviceAccountJson() {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        ObjectNode account = mapper.createObjectNode();
        account.put("type", "service_account");
        account.put("client_email", "path@demo.iam.gserviceaccount.com");
        account.put("private_key", pem);
        account.put("token_uri", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
        return account.toString();
    }

    private Provider provider() {
        Provider provider = new Provider("vertex", "Vertex", ProviderType.VERTEX,
                "http://127.0.0.1:" + server.getAddress().getPort());
        provider.setId(1L);
        provider.setProject("demo");
        provider.setLocation("us-central1");
        return provider;
    }

    @Test
    void signsAnAssertionExchangesItAndCachesTheToken() throws Exception {
        VertexTokenService tokens = new VertexTokenService(mapper);
        GeminiAdapter adapter = new GeminiAdapter(mapper, ProviderType.VERTEX, tokens);
        ModelRoute route = new ModelRoute();
        route.setPublicModelId("gemini-2.5-pro");
        route.setUpstreamModelId("gemini-2.5-pro");
        ChatRequestSpec spec = ChatRequestSpec.parse((ObjectNode) mapper.readTree("""
                {"model":"gemini-2.5-pro","messages":[{"role":"user","content":"hi"}]}
                """));

        UpstreamRequest request = adapter.build(spec, route, provider(), serviceAccountJson(), false);
        adapter.build(spec, route, provider(), serviceAccountJson(), false);

        java.net.http.HttpRequest send = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(request.url()))
                .header("Content-Type", "application/json")
                .header("Authorization", request.headers().get("Authorization"))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(request.body()))
                .build();
        java.net.http.HttpClient.newHttpClient().send(send,
                java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(request.url()).isEqualTo("http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1/projects/demo/locations/us-central1/publishers/google/models/"
                + "gemini-2.5-pro:generateContent");
        assertThat(request.headers()).containsEntry("Authorization", "Bearer ya29.test");
        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThat(authorization.get()).isEqualTo("Bearer ya29.test");
    }

    @Test
    void theAssertionIsAValidRs256Jwt() throws Exception {
        new VertexTokenService(mapper).accessToken(provider(), serviceAccountJson());

        String[] parts = assertion.get().split("\\.");
        assertThat(parts).hasSize(3);
        String signingInput = parts[0] + "." + parts[1];
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();

        var claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.path("iss").asText()).isEqualTo("path@demo.iam.gserviceaccount.com");
        assertThat(claims.path("scope").asText()).isEqualTo("https://www.googleapis.com/auth/cloud-platform");
        assertThat(claims.path("aud").asText())
                .isEqualTo("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    }
}
