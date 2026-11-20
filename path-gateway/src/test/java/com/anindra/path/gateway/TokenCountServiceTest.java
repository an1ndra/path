package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenCountServiceTest {

    private final RouteResolver resolver = mock(RouteResolver.class);
    private final GatewayProperties props = new GatewayProperties();
    private final TokenCountService service = new TokenCountService(resolver, props);
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode body(String model) throws Exception {
        return (ObjectNode) mapper.readTree("{\"model\":\"" + model + "\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"you are terse\"},"
                + "{\"role\":\"user\",\"content\":\"hello\"}]}");
    }

    private ClientKey key(String allowed) {
        ClientKey key = new ClientKey();
        key.setId(1L);
        key.setName("test");
        key.setAllowedModels(allowed);
        return key;
    }

    private RouteResolver.ResolvedRoute route() {
        ModelRoute route = new ModelRoute("gpt-4o-mini", 1L, "gpt-4o-mini",
                new BigDecimal("0.15"), new BigDecimal("0.60"), 10);
        route.setContextLength(128000);
        Provider provider = new Provider("openai", "OpenAI", ProviderType.OPENAI,
                "https://api.openai.com/v1");
        return new RouteResolver.ResolvedRoute(route, provider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void countsASystemOnlyPromptThatChatWouldReject() throws Exception {
        when(resolver.resolve(any(), eq(1L))).thenReturn(List.of(route()));

        Map<String, Object> out = service.count(body("gpt-4o-mini"), key(null));

        assertThat(out.get("object")).isEqualTo("token_count");
        assertThat((Integer) out.get("prompt_tokens")).isPositive();
        assertThat(out.get("estimated")).isEqualTo(false);
        Map<String, Object> fits = (Map<String, Object>) out.get("fits");
        assertThat(fits.get("context_window")).isEqualTo(128000);
    }

    @Test
    void rejectsAModelOutsideTheKeyAllowlist() throws Exception {
        assertThatThrownBy(() -> service.count(body("gpt-4o"), key("claude-*")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void unknownModelIs404() throws Exception {
        when(resolver.resolve(any(), eq(1L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.count(body("nope"), key(null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not exist");
    }
}
