package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.anindra.path.repo.UsageRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiAdapter adapter = new GeminiAdapter(mapper, ProviderType.GEMINI,
            Mockito.mock(VertexTokenService.class));

    private Provider provider() {
        return new Provider("gemini", "Gemini", ProviderType.GEMINI,
                "https://generativelanguage.googleapis.com/v1beta");
    }

    private ModelRoute route(String model) {
        ModelRoute route = new ModelRoute();
        route.setPublicModelId(model);
        route.setUpstreamModelId(model);
        route.setInputPricePerMillion(new BigDecimal("1.00"));
        route.setOutputPricePerMillion(new BigDecimal("2.00"));
        return route;
    }

    private ChatRequestSpec spec(String json) throws Exception {
        return ChatRequestSpec.parse((ObjectNode) mapper.readTree(json));
    }

    @Test
    void translatesThinkingSafetyAndToolsIntoTheNativeShape() throws Exception {
        ChatRequestSpec spec = spec("""
                {"model":"gemini-2.5-pro",
                 "messages":[
                   {"role":"system","content":"be precise"},
                   {"role":"user","content":"weather in Pune?"}],
                 "temperature":0.3,
                 "max_tokens":256,
                 "thinkingConfig":{"thinkingBudget":1024,"includeThoughts":true},
                 "safetySettings":[{"category":"HARM_CATEGORY_HATE_SPEECH","threshold":"BLOCK_ONLY_HIGH"}],
                 "tools":[{"type":"function","function":{"name":"weather","description":"Weather",
                   "parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}]}
                """);

        UpstreamRequest request = adapter.build(spec, route("gemini-2.5-pro"),
                provider(), "api-key-1", false);

        assertThat(request.url()).isEqualTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent");
        assertThat(request.headers()).containsEntry("x-goog-api-key", "api-key-1");

        JsonNode sent = mapper.readTree(request.body());
        assertThat(sent.path("systemInstruction").path("parts").get(0).path("text").asText())
                .isEqualTo("be precise");
        assertThat(sent.path("contents").get(0).path("role").asText()).isEqualTo("user");
        assertThat(sent.path("contents").get(0).path("parts").get(0).path("text").asText())
                .isEqualTo("weather in Pune?");
        assertThat(sent.path("generationConfig").path("temperature").asDouble()).isEqualTo(0.3);
        assertThat(sent.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(256);
        assertThat(sent.path("generationConfig").path("thinkingConfig").path("thinkingBudget").asInt())
                .isEqualTo(1024);
        assertThat(sent.path("generationConfig").path("thinkingConfig").path("includeThoughts").asBoolean())
                .isTrue();
        assertThat(sent.path("safetySettings").get(0).path("category").asText())
                .isEqualTo("HARM_CATEGORY_HATE_SPEECH");
        assertThat(sent.path("tools").get(0).path("functionDeclarations").get(0).path("name").asText())
                .isEqualTo("weather");
    }

    @Test
    void reasoningEffortBecomesAThinkingBudget() throws Exception {
        ChatRequestSpec spec = spec("""
                {"model":"gemini-2.5-pro","messages":[{"role":"user","content":"hi"}],
                 "reasoning_effort":"high"}
                """);

        UpstreamRequest request = adapter.build(spec, route("gemini-2.5-pro"), provider(), "k", false);

        assertThat(mapper.readTree(request.body())
                .path("generationConfig").path("thinkingConfig").path("thinkingBudget").asInt())
                .isEqualTo(8192);
    }

    @Test
    void aParameterGeminiCannotHonourIs422() throws Exception {
        ChatRequestSpec spec = spec("""
                {"model":"gemini-2.5-pro","messages":[{"role":"user","content":"hi"}],
                 "frequency_penalty":0.5}
                """);

        assertThatThrownBy(() -> adapter.build(spec, route("gemini-2.5-pro"), provider(), "k", false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("unsupported_parameter"));
    }

    @Test
    void normalisesTheResponseAndCountsThinkingAsCompletion() throws Exception {
        JsonNode nativeResponse = mapper.readTree("""
                {"candidates":[{"content":{"role":"model","parts":[
                    {"text":"Let me think","thought":true},
                    {"text":"It is sunny"}]},"finishReason":"STOP",
                  "safetyRatings":[{"category":"HARM_CATEGORY_HATE_SPEECH","probability":"NEGLIGIBLE"}]}],
                 "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,
                   "thoughtsTokenCount":7,"cachedContentTokenCount":4,"totalTokenCount":22}}
                """);
        ChatRequestSpec spec = spec("""
                {"model":"gemini-2.5-pro","messages":[{"role":"user","content":"hi"}]}
                """);

        JsonNode normalised = adapter.parseFullResponse(nativeResponse, spec, route("gemini-2.5-pro"));
        assertThat(normalised.path("choices").get(0).path("message").path("content").asText())
                .isEqualTo("It is sunny");
        assertThat(normalised.path("choices").get(0).path("finish_reason").asText()).isEqualTo("stop");
        assertThat(normalised.path("usage").path("completion_tokens").asInt()).isEqualTo(12);
        assertThat(normalised.path("usage").path("completion_tokens_details").path("reasoning_tokens").asInt())
                .isEqualTo(7);

        TokenUsage usage = adapter.parseUsage(nativeResponse);
        assertThat(usage.prompt()).isEqualTo(10);
        assertThat(usage.completion()).isEqualTo(12);
        assertThat(usage.reasoning()).isEqualTo(7);
        assertThat(usage.cached()).isEqualTo(4);
    }

    @Test
    void thinkingTokensArePricedAsOutput() throws Exception {
        JsonNode nativeResponse = mapper.readTree("""
                {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":1000000,"candidatesTokenCount":1000000,
                   "thoughtsTokenCount":1000000,"totalTokenCount":3000000}}
                """);
        TokenUsage usage = adapter.parseUsage(nativeResponse);

        UsageService usageService = new UsageService(
                Mockito.mock(UsageRecordRepository.class),
                Mockito.mock(ApiKeyService.class),
                new SimpleMeterRegistry(),
                new GatewayProperties());
        UsageService.CostBreakdown cost = usageService.cost(route("gemini-2.5-pro"), usage);

        // prompt 1M at $1/M = $1.00; completion 1M + 1M thoughts at $2/M = $4.00.
        assertThat(usage.completion()).isEqualTo(2_000_000);
        assertThat(cost.outputUsd()).isEqualByComparingTo("4.00000000");
        assertThat(cost.inputUsd()).isEqualByComparingTo("1.00000000");
        assertThat(cost.totalUsd()).isEqualByComparingTo("5.00000000");
    }

    @Test
    void aBlockedPromptIsA400NamingTheCategory() throws Exception {
        JsonNode blocked = mapper.readTree("""
                {"promptFeedback":{"blockReason":"SAFETY","safetyRatings":[
                  {"category":"HARM_CATEGORY_DANGEROUS_CONTENT","probability":"HIGH","blocked":true}]}}
                """);
        ChatRequestSpec spec = spec("""
                {"model":"gemini-2.5-pro","messages":[{"role":"user","content":"nh"}]}
                """);

        assertThatThrownBy(() -> adapter.parseFullResponse(blocked, spec, route("gemini-2.5-pro")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(400);
                    assertThat(api.code()).isEqualTo("content_blocked");
                    assertThat(api.getMessage()).contains("HARM_CATEGORY_DANGEROUS_CONTENT");
                });
    }

    @Test
    void streamsContentAndKeepsTheUsage() throws Exception {
        ChatRequestSpec spec = ChatRequestSpec.parse((ObjectNode) mapper.readTree("""
                {"model":"gemini-2.5-pro","messages":[{"role":"user","content":"hi"}]}
                """));
        StreamTranslator translator = adapter.newStreamTranslator(spec, route("gemini-2.5-pro"));

        List<JsonNode> first = translator.translate(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hel\"}]}}]}");
        List<JsonNode> second = translator.translate(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"lo\"}]},\"finishReason\":\"STOP\"}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2,"
                        + "\"thoughtsTokenCount\":1,\"totalTokenCount\":6}}");

        assertThat(first).hasSize(1);
        assertThat(first.get(0).path("choices").get(0).path("delta").path("content").asText())
                .isEqualTo("Hel");
        assertThat(second.get(0).path("choices").get(0).path("finish_reason").asText()).isEqualTo("stop");
        assertThat(translator.done()).isTrue();
        assertThat(translator.usage().completion()).isEqualTo(3);
        assertThat(translator.usage().reasoning()).isEqualTo(1);
    }
}
