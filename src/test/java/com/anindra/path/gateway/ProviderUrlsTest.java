package com.anindra.path.gateway;

import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderUrlsTest {

    private Provider provider(String baseUrl, String apiVersion) {
        Provider provider = new Provider("p", "p", ProviderType.OPENAI, baseUrl);
        provider.setApiVersion(apiVersion);
        return provider;
    }

    @Test
    void plainProvidersUseTheOpenAiShape() {
        Provider openai = provider("https://api.openai.com/v1", null);

        assertThat(ProviderUrls.chat(openai, "gpt-4o-mini"))
                .isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(ProviderUrls.embeddings(openai, "text-embedding-3-small"))
                .isEqualTo("https://api.openai.com/v1/embeddings");
        assertThat(ProviderUrls.headers(openai, "sk-test", false))
                .containsEntry("Authorization", "Bearer sk-test")
                .doesNotContainKey("api-key");
    }

    @Test
    void azurePutsTheDeploymentInThePathAndTheVersionInTheQuery() {
        Provider azure = provider("https://acme.openai.azure.com", "2024-10-21");

        assertThat(ProviderUrls.chat(azure, "gpt-4o-prod"))
                .isEqualTo("https://acme.openai.azure.com/openai/deployments/gpt-4o-prod"
                        + "/chat/completions?api-version=2024-10-21");
        assertThat(ProviderUrls.embeddings(azure, "embed-prod"))
                .isEqualTo("https://acme.openai.azure.com/openai/deployments/embed-prod"
                        + "/embeddings?api-version=2024-10-21");
    }

    @Test
    void azureAuthenticatesWithTheApiKeyHeader() {
        Map<String, String> headers = ProviderUrls.headers(
                provider("https://acme.openai.azure.com", "2024-10-21"), "secret", true);

        assertThat(headers).containsEntry("api-key", "secret")
                .containsEntry("Accept", "text/event-stream")
                .doesNotContainKey("Authorization");
    }

    @Test
    void aTrailingSlashDoesNotDoubleUp() {
        Provider azure = provider("https://acme.openai.azure.com/", "2024-10-21");
        assertThat(ProviderUrls.chat(azure, "deployment"))
                .startsWith("https://acme.openai.azure.com/openai/deployments/deployment/chat")
                .doesNotContain("//openai");
    }

    @Test
    void probeUsesTheDeploymentListingForAzure() {
        Provider azure = provider("https://acme.openai.azure.com", "2024-10-21");
        assertThat(ProviderUrls.models(azure))
                .isEqualTo("https://acme.openai.azure.com/openai/models?api-version=2024-10-21");
    }

    @Test
    void blankApiVersionIsNotTreatedAsAzure() {
        assertThat(ProviderUrls.isAzure(provider("https://x/v1", "   "))).isFalse();
    }
}
