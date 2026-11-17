package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Project;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.anindra.path.repo.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectBudgetAlertTest {

    private final ApiKeyService apiKeys = mock(ApiKeyService.class);
    private final UsageService usage = mock(UsageService.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final com.anindra.path.admin.AlertService alerts =
            mock(com.anindra.path.admin.AlertService.class);
    private final GatewayProperties props = new GatewayProperties();
    private final CreditGuard guard = new CreditGuard(apiKeys, usage, props, alerts, projects);
    private final ObjectMapper mapper = new ObjectMapper();

    private Project project(String dailyLimit) {
        Project project = new Project();
        project.setId(7L);
        project.setSlug("acme");
        project.setName("Acme");
        project.setDailyLimitUsd(dailyLimit == null ? null : new BigDecimal(dailyLimit));
        return project;
    }

    private ClientKey key(long projectId) {
        ClientKey key = new ClientKey();
        key.setId(1L);
        key.setName("test");
        key.setProjectId(projectId);
        return key;
    }

    private RouteResolver.ResolvedRoute route() {
        ModelRoute route = new ModelRoute("gpt-4o-mini", 1L, "gpt-4o-mini",
                new BigDecimal("0.15"), new BigDecimal("0.60"), 10);
        Provider provider = new Provider("openai", "OpenAI", ProviderType.OPENAI,
                "https://api.openai.com/v1");
        return new RouteResolver.ResolvedRoute(route, provider);
    }

    private void stubCost() {
        when(usage.cost(any(), any())).thenReturn(new UsageService.CostBreakdown(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.0001")));
        when(apiKeys.reserve(any(), any())).thenReturn(true);
    }

    private ChatRequestSpec spec() throws Exception {
        return ChatRequestSpec.parse((ObjectNode) mapper.readTree(
                "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));
    }

    @Test
    void warnsOnceWhenTheProjectBudgetIsNearlySpent() throws Exception {
        when(projects.findById(7L)).thenReturn(Optional.of(project("10.00")));
        when(usage.spendForProject(eq(7L), any(Instant.class))).thenReturn(new BigDecimal("9.00"));
        when(apiKeys.remaining(any())).thenReturn(new BigDecimal("10.00"));
        stubCost();

        guard.check(key(7L), spec(), route());
        guard.check(key(7L), spec(), route());

        // The dedupe key is stable per project, window and period: AlertService.notifyOnce
        // swallows the repeats, so the guard may ask twice but one event goes out.
        verify(alerts, times(2)).notifyOnce(contains("project.budget_low:7:daily"), eq("project.budget_low"), anyMap());
        verify(alerts, never()).notifyOnce(contains("project.budget_exhausted"), anyString(), anyMap());
    }

    @Test
    void stopsTheCallAndAlertsWhenTheProjectBudgetIsGone() throws Exception {
        when(projects.findById(7L)).thenReturn(Optional.of(project("10.00")));
        when(usage.spendForProject(eq(7L), any(Instant.class))).thenReturn(new BigDecimal("10.00"));

        assertThatThrownBy(() -> guard.check(key(7L), spec(), route()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("acme");

        verify(alerts, times(1)).notifyOnce(contains("project.budget_exhausted:7:daily"),
                eq("project.budget_exhausted"), anyMap());
    }

    @Test
    void doesNotAlertWhenTheProjectHasNoLimit() throws Exception {
        when(projects.findById(7L)).thenReturn(Optional.of(project(null)));
        when(apiKeys.remaining(any())).thenReturn(new BigDecimal("10.00"));
        stubCost();

        guard.check(key(7L), spec(), route());

        verify(alerts, never()).notifyOnce(anyString(), anyString(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePayloadNamesTheWindowAndTheMoney() throws Exception {
        when(projects.findById(7L)).thenReturn(Optional.of(project("10.00")));
        when(usage.spendForProject(eq(7L), any(Instant.class))).thenReturn(new BigDecimal("9.50"));
        when(apiKeys.remaining(any())).thenReturn(new BigDecimal("10.00"));
        stubCost();

        guard.check(key(7L), spec(), route());
        verify(alerts).notifyOnce(contains("project.budget_low"), eq("project.budget_low"),
                org.mockito.ArgumentMatchers.argThat((Map<String, Object> payload) ->
                        "acme".equals(payload.get("project"))
                                && "daily".equals(payload.get("window"))
                                && payload.get("remaining_usd") != null));
    }
}
