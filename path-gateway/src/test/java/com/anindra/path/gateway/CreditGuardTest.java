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
    private final com.anindra.path.repo.ProjectRepository projects =
            mock(com.anindra.path.repo.ProjectRepository.class);
    private final EndUserBudget endUserBudget = mock(EndUserBudget.class);
    private final CreditGuard guard = new CreditGuard(apiKeys, endUserBudget, usage, props,
            new com.anindra.path.admin.AlertService(props, new ObjectMapper(), org.mockito.Mockito.mock(com.anindra.path.admin.MailSettingsService.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), projects);
    private final ObjectMapper mapper = new ObjectMapper();

    @org.junit.jupiter.api.BeforeEach
    void stubCost() {
        // Estimates are computed before the balance is read, so every test that gets past
        // the budget windows needs a price.
        org.mockito.Mockito.lenient().when(usage.cost(any(), any()))
                .thenReturn(new UsageService.CostBreakdown(BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("0.0001")));
    }

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
    void allowlistMatchesIdsAndGlobs() {
        ClientKey key = key(null, null, null);

        key.setAllowedModels("gpt-4o-mini,claude-*");
        assertThat(CreditGuard.allows(key, "gpt-4o-mini")).isTrue();
        assertThat(CreditGuard.allows(key, "claude-3.5-haiku")).isTrue();
        assertThat(CreditGuard.allows(key, "llama-3.1-8b")).isFalse();

        key.setAllowedModels("gpt-*");
        assertThat(CreditGuard.allows(key, "llama-3.1-8b")).isFalse();

        key.setAllowedModels(null);
        assertThat(CreditGuard.allows(key, "anything")).isTrue();
    }

    @Test
    void reservesTheExpectedCostRatherThanTheMaxTokensCap() throws Exception {
        ClientKey key = key(null, null, null);
        key.setCreditLimitUsd(new BigDecimal("0.001"));
        key.setUsedUsd(BigDecimal.ZERO);
        when(apiKeys.remaining(any())).thenReturn(new BigDecimal("0.001"));
        when(apiKeys.reserve(eq(1L), any())).thenReturn(true);
        when(usage.cost(any(), any())).thenReturn(
                new UsageService.CostBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.0100")),
                new UsageService.CostBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.0005")));

        // max_tokens is 8000, but a typical completion fits in the remaining balance.
        BigDecimal reserved = guard.check(key, spec("8000"), route()).keyUsd();

        assertThat(reserved).isEqualByComparingTo("0.001");
    }

    @Test
    void blocksAModelOutsideTheAllowlist() throws Exception {
        ClientKey key = key(null, null, null);
        key.setAllowedModels("gpt-4o-mini");
        when(usage.spendFor(eq(1L), any(Instant.class))).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> guard.check(key, specOf("claude-3.5-haiku"), route()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "model_not_allowed");
    }

    private ChatRequestSpec specOf(String model) throws Exception {
        return ChatRequestSpec.parse((ObjectNode) mapper.readTree(
                "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));
    }

    @Test
    void leavesSmallerMaxTokensAlone() throws Exception {
        ChatRequestSpec spec = spec("40");
        when(usage.spendFor(eq(1L), any(Instant.class))).thenReturn(BigDecimal.ZERO);

        guard.check(key(null, null, 100), spec, route());

        assertThat(spec.maxTokens()).isEqualTo(40);
    }
}
