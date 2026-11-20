package com.anindra.path.admin;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The audit table is readable through /admin/audit, so it must never hold a credential. */
class AuditRedactionTest {

    record ProviderInput(String slug, String baseUrl, String apiKey) {
    }

    @Test
    void providerApiKeysAreRedacted() {
        String json = String.valueOf(AuditService.sanitise(
                new ProviderInput("openai", "https://api.openai.com/v1", "sk-super-secret-value")));

        assertThat(json).doesNotContain("sk-super-secret-value");
        assertThat(json).contains("\"apiKey\":\"***\"");
        assertThat(json).contains("openai");
    }

    @Test
    void nestedAndAlternateNamesAreRedacted() {
        String json = String.valueOf(AuditService.sanitise(Map.of(
                "provider", Map.of("api_key", "sk-nested", "base_url", "https://x"),
                "new_key", "base64-master-key",
                "password", "hunter2",
                "note", "kept")));

        assertThat(json).doesNotContain("sk-nested")
                .doesNotContain("base64-master-key")
                .doesNotContain("hunter2");
        assertThat(json).contains("kept").contains("https://x");
    }
}
