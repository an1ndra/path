package com.anindra.path.seed;

import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.anindra.path.gateway.ApiKeyService;
import com.anindra.path.repo.ClientKeyRepository;
import com.anindra.path.repo.ModelRouteRepository;
import com.anindra.path.repo.ProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * First-boot bootstrap: three providers, a small route table and (optionally) a demo
 * client key. Everything it creates can be changed through /admin.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private final ProviderRepository providers;
    private final ModelRouteRepository routes;
    private final ClientKeyRepository clientKeys;
    private final ApiKeyService apiKeys;
    private final GatewayProperties props;
    private final SeedState seedState;
    private final com.anindra.path.repo.ProjectRepository projectsRepository;
    private final org.springframework.core.env.Environment environment;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean fresh = providers.count() == 0;
        Map<String, Provider> bySlug = ensureProviders();
        if (fresh && routes.count() == 0) {
            seedRoutes(bySlug);
        }
        if (projectsRepository.count() == 0) {
            projectsRepository.save(new com.anindra.path.domain.Project("default", "Default project"));
        }
        boolean prod = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
        if (props.isBootstrapDemoKey() && !prod && clientKeys.count() == 0) {
            var created = apiKeys.create("demo-local", null, props.getDefaultRpmLimit());
            log.info("Created demo API key (no credit limit): {}", created.plaintext());
        } else if (prod && props.isBootstrapDemoKey()) {
            log.warn("Ignoring gateway.bootstrap-demo-key in the prod profile; no demo key created.");
        }
        seedState.markReady();
        log.info("Gateway ready. Admin API: {}  |  Public API: /v1/chat/completions, /v1/models",
                props.getAdminToken() == null || props.getAdminToken().isBlank()
                        ? "disabled (set GATEWAY_ADMIN_TOKEN)"
                        : "enabled (send X-Admin-Token)");
    }

    private Map<String, Provider> ensureProviders() {
        Map<String, Provider> bySlug = new LinkedHashMap<>();
        bySlug.put("openai", providers.findBySlug("openai").orElseGet(() -> providers.save(
                new Provider("openai", "OpenAI", ProviderType.OPENAI, "https://api.openai.com/v1"))));
        bySlug.put("anthropic", providers.findBySlug("anthropic").orElseGet(() -> providers.save(
                new Provider("anthropic", "Anthropic", ProviderType.ANTHROPIC, "https://api.anthropic.com/v1"))));
        bySlug.put("groq", providers.findBySlug("groq").orElseGet(() -> providers.save(
                new Provider("groq", "Groq", ProviderType.OPENAI, "https://api.groq.com/openai/v1"))));
        bySlug.put("gemini", providers.findBySlug("gemini").orElseGet(() -> providers.save(
                new Provider("gemini", "Google Gemini", ProviderType.OPENAI,
                        "https://generativelanguage.googleapis.com/v1beta/openai"))));
        return bySlug;
    }

    private void seedRoutes(Map<String, Provider> p) {
        Long openai = p.get("openai").getId();
        Long anthropic = p.get("anthropic").getId();
        Long groq = p.get("groq").getId();

        route("gpt-4o-mini", openai, "gpt-4o-mini", "0.15", "0.075", null, "0.60", 10, 128_000,
                "Fast, cheap OpenAI model");
        route("gpt-4o", openai, "gpt-4o", "2.50", "1.25", null, "10.00", 10, 128_000,
                "Flagship OpenAI multimodal model");
        route("gpt-4.1-mini", openai, "gpt-4.1-mini", "0.40", "0.10", null, "1.60", 10, 1_047_576,
                "Long-context OpenAI mini model");

        route("claude-3.5-haiku", anthropic, "claude-3-5-haiku-latest", "0.80", "0.08", "1.00", "4.00",
                10, 200_000, "Fast Anthropic model");
        route("claude-sonnet-4.5", anthropic, "claude-sonnet-4-5", "3.00", "0.30", "3.75", "15.00",
                10, 200_000, "Anthropic balanced model");

        route("llama-3.3-70b", groq, "llama-3.3-70b-versatile", "0.59", null, null, "0.79", 10, 131_072,
                "Open-weights 70B on Groq");
        route("llama-3.1-8b", groq, "llama-3.1-8b-instant", "0.05", null, null, "0.08", 10, 131_072,
                "Cheapest Groq model");

        // Deliberate cross-provider fallback chain: try OpenAI, fail over to Groq.
        route("auto-fast", openai, "gpt-4o-mini", "0.15", "0.075", null, "0.60", 1, 128_000,
                "Alias: OpenAI gpt-4o-mini first, Groq llama-3.3-70b as fallback");
        route("auto-fast", groq, "llama-3.3-70b-versatile", "0.59", null, null, "0.79", 2, 131_072,
                "Alias fallback");
    }

    private void route(String publicId, Long providerId, String upstreamId, String input,
                       String cachedInput, String cacheWrite, String output,
                       int priority, int contextLength, String description) {
        ModelRoute route = new ModelRoute(publicId, providerId, upstreamId,
                new BigDecimal(input), new BigDecimal(output), priority);
        if (cachedInput != null) {
            route.setCachedInputPricePerMillion(new BigDecimal(cachedInput));
        }
        if (cacheWrite != null) {
            route.setCacheWritePricePerMillion(new BigDecimal(cacheWrite));
        }
        route.setContextLength(contextLength);
        route.setDescription(description);
        routes.save(route);
    }
}
