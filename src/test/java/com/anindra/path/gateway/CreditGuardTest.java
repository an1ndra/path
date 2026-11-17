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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreditGuardTest {

    private final ApiKeyService apiKeys = mock(ApiKeyService.class);
    private final UsageService usage = mock(UsageService.class);
    private final GatewayProperties props = new GatewayProperties();
    private final CreditGuard guard = new CreditGuard(apiKeys, usage, props,
            new com.anindra.path.admin.AlertService(props, new ObjectMapper()));
    private final ObjectMapper mapper = new ObjectMapper();

    private ClientKey key(BigDecimal daily, BigDecimal monthly, Integer maxOutput) {
        ClientKey key = new ClientKey();
        key.setId(1L);
        key.setName("test");
        key.setDailyLimitUsd(daily);
        key.setMonthlyLimitUsd(monthly);
        key.setMaxOutputTokens(maxOutput);
        return key;
    }

    private RouteResolver.ResolvedRoute route() {
        ModelRoute route = new ModelRoute("gpt-4o-mini", 1L, "gpt-4o-mini",
                new BigDecimal("0.15"), new BigDecimal("0.60"), 10);
        Provider provider = new Provider("openai", "OpenAI", ProviderType.OPENAI, "https://api.openai.com/v1");
        return new RouteResolver.ResolvedRoute(route, provider);
    }

    private ChatRequestSpec spec(String maxTokens) throws Exception {
        String json = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]"
                + (maxTokens == null ? "" : ",\"max_tokens\":" + maxTokens) + "}";
        return ChatRequestSpec.parse((ObjectNode) mapper.readTree(json));
    }

    @Test
    void rejectsWhenTheDailyBudgetIsSpent() throws Exception {
        when(usage.spendFor(eq(1L), any(Instant.class))).thenReturn(new BigDecimal("5.00"));

        assertThatThrownBy(() -> guard.check(key(new BigDecimal("5.00"), null, null), spec(null), route()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Daily budget");
    }

    @Test
    void allowsWhenUnderTheWindow() throws Exception {
        when(usage.spendFor(eq(1L), any(Instant.class))).thenReturn(new BigDecimal("1.00"));

        guard.check(key(new BigDecimal("5.00"), new BigDecimal("50.00"), null), spec(null), route());
    }

    @Test
    void clampsMaxTokensToTheKeyCap() throws Exception {
        ChatRequestSpec spec = spec("5000");
        when(usage.spendFor(eq(1L), any(Instant.class))).thenReturn(BigDecimal.ZERO);

        guard.check(key(null, null, 100), spec, route());

        assertThat(spec.maxTokens()).isEqualTo(100);
        assertThat(spec.body().get("max_tokens").asInt()).isEqualTo(100);
    }

    @Test
    void leavesSmallerMaxTokensAlone() throws Exception {
        ChatRequestSpec spec = spec("40");
        when(usage.spendFor(eq(1L), any(Instant.class))).thenReturn(BigDecimal.ZERO);

        guard.check(key(null, null, 100), spec, route());

        assertThat(spec.maxTokens()).isEqualTo(40);
    }
}
