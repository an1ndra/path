package com.anindra.path.gateway;

import com.anindra.path.domain.Provider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * URL and auth shapes for a provider.
 *
 * <p>A provider with {@code apiVersion} set is an Azure OpenAI resource: the model id is a
 * deployment name in the path, the version is a query parameter, and the credential goes
 * in {@code api-key} rather than a bearer header. Everything else is the plain OpenAI
 * shape.
 */
public final class ProviderUrls {

    private ProviderUrls() {
    }

    public static boolean isAzure(Provider provider) {
        return provider.getApiVersion() != null && !provider.getApiVersion().isBlank();
    }

    public static String chat(Provider provider, String model) {
        return isAzure(provider)
                ? azurePath(provider, model, "/chat/completions")
                : join(provider.getBaseUrl(), "/chat/completions");
    }

    public static String embeddings(Provider provider, String model) {
        return isAzure(provider)
                ? azurePath(provider, model, "/embeddings")
                : join(provider.getBaseUrl(), "/embeddings");
    }

    /** Azure has no /models; a deployment listing is the closest equivalent. */
    public static String models(Provider provider) {
        return isAzure(provider)
                ? join(provider.getBaseUrl(), "/openai/models?api-version=" + provider.getApiVersion())
                : join(provider.getBaseUrl(), "/models");
    }

    public static String moderation(Provider provider) {
        return isAzure(provider)
                ? join(provider.getBaseUrl(),
                        "/openai/deployments/" + provider.getApiVersion() + "/moderations")
                : join(provider.getBaseUrl(), "/moderations");
    }

    public static Map<String, String> headers(Provider provider, String apiKey, boolean stream) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (isAzure(provider)) {
            headers.put("api-key", apiKey);
        } else {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        headers.put("Accept", stream ? "text/event-stream" : "application/json");
        // Java's HttpClient identifies itself as "Java-http-client/21". Some upstreams
        // (OpenCode Go among them) require a client to name itself instead of looking like
        // a generic library, and the version helps when reading provider-side logs.
        headers.put("User-Agent", "path-gateway");
        return headers;
    }

    private static String azurePath(Provider provider, String deployment, String suffix) {
        String base = provider.getBaseUrl().endsWith("/")
                ? provider.getBaseUrl().substring(0, provider.getBaseUrl().length() - 1)
                : provider.getBaseUrl();
        return base + "/openai/deployments/" + deployment + suffix
                + "?api-version=" + provider.getApiVersion();
    }

    private static String join(String base, String path) {
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + path;
    }
}
