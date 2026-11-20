package com.anindra.path.gateway;

import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.Provider;
import com.anindra.path.secret.SecretCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the upstream credential for a provider: a key stored through the admin API
 * wins, then the well-known environment variable, then a generated per-slug variable.
 */
@Component
@RequiredArgsConstructor
public class ProviderCredentials {

    private final SecretCipher cipher;
    private final GatewayProperties props;

    public String apiKey(Provider provider) {
        if (provider.getApiKeyCipher() != null && !provider.getApiKeyCipher().isBlank()) {
            String decrypted = cipher.decrypt(provider.getApiKeyCipher());
            if (decrypted != null && !decrypted.isBlank()) {
                return decrypted;
            }
        }
        String fromConfig = configKey(provider.getSlug());
        if (fromConfig != null) {
            return fromConfig;
        }
        return envKey(provider.getSlug());
    }

    /** Where the credential comes from, for admin display. */
    public String source(Provider provider) {
        if (provider.getApiKeyCipher() != null && !provider.getApiKeyCipher().isBlank()) {
            return "database";
        }
        if (configKey(provider.getSlug()) != null) {
            return "config";
        }
        if (envKey(provider.getSlug()) != null) {
            return "env";
        }
        return null;
    }

    /** Never returns the whole key: just the last four characters. */
    public String hint(Provider provider) {
        String key = apiKey(provider);
        if (key == null) {
            return null;
        }
        if (key.length() <= 8) {
            return "****";
        }
        return "****" + key.substring(key.length() - 4);
    }

    private String configKey(String slug) {
        String value = switch (slug.toLowerCase()) {
            case "openai" -> props.getProviders().getOpenai().getApiKey();
            case "anthropic" -> props.getProviders().getAnthropic().getApiKey();
            case "groq" -> props.getProviders().getGroq().getApiKey();
            case "gemini" -> props.getProviders().getGemini().getApiKey();
            default -> null;
        };
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String envKey(String slug) {
        String envName = "GATEWAY_UPSTREAM_"
                + slug.toUpperCase().replaceAll("[^A-Z0-9]", "_")
                + "_KEY";
        String value = System.getenv(envName);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
