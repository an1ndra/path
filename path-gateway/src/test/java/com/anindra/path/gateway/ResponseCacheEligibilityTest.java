package com.anindra.path.gateway;

import com.anindra.path.domain.ModelRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseCacheEligibilityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponseCacheService cache = new ResponseCacheService(null, null);

    private ModelRoute route(boolean enabled) {
        ModelRoute route = new ModelRoute("gpt-4o-mini", 1L, "gpt-4o-mini", null, null, 10);
        route.setCacheEnabled(enabled);
        return route;
    }

    private ChatRequestSpec spec(String json) throws Exception {
        return ChatRequestSpec.parse((ObjectNode) mapper.readTree(json));
    }

    @Test
    void requiresTheRouteToOptIn() throws Exception {
        ChatRequestSpec spec = spec("""
                {"model":"gpt-4o-mini","temperature":0,
                 "messages":[{"role":"user","content":"hi"}]}
                """);
        assertThat(ResponseCacheService.eligible(spec, route(true))).isTrue();
        assertThat(ResponseCacheService.eligible(spec, route(false))).isFalse();
    }

    @Test
    void requiresAnExplicitZeroTemperature() throws Exception {
        ChatRequestSpec noTemp = spec("""
                {"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}
                """);
        ChatRequestSpec warm = spec("""
                {"model":"gpt-4o-mini","temperature":0.7,
                 "messages":[{"role":"user","content":"hi"}]}
                """);
        assertThat(ResponseCacheService.eligible(noTemp, route(true))).isFalse();
        assertThat(ResponseCacheService.eligible(warm, route(true))).isFalse();
    }

    @Test
    void refusesRequestsWithTools() throws Exception {
        ChatRequestSpec spec = spec("""
                {"model":"gpt-4o-mini","temperature":0,
                 "messages":[{"role":"user","content":"hi"}],
                 "tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}]}
                """);
        assertThat(ResponseCacheService.eligible(spec, route(true))).isFalse();
    }

    @Test
    void keyIsScopedToTheClientAndPayload() {
        String keyA = cache.key(1L, "gpt-4o-mini", "{\"a\":1}");
        String keyB = cache.key(1L, "gpt-4o-mini", "{\"a\":1}");
        String otherClient = cache.key(2L, "gpt-4o-mini", "{\"a\":1}");
        String otherBody = cache.key(1L, "gpt-4o-mini", "{\"a\":2}");

        assertThat(keyA).isEqualTo(keyB).hasSize(64);
        assertThat(otherClient).isNotEqualTo(keyA);
        assertThat(otherBody).isNotEqualTo(keyA);
    }
}
