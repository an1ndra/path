package com.anindra.path.admin;

import com.anindra.path.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptLogServiceTest {

    private PromptLogService service() {
        return new PromptLogService(null, new GatewayProperties());
    }

    @Test
    void isDisabledByDefault() {
        assertThat(service().enabled()).isFalse();
    }

    @Test
    void redactsProviderKeysAndBearerTokens() {
        assertThat(PromptLogService.redact("my key is sk-abcdefghijklmnopqrstuvwx"))
                .isEqualTo("my key is [redacted]");
        assertThat(PromptLogService.redact("Authorization: Bearer abcdefghijklmnop1234"))
                .contains("[redacted]");
        assertThat(PromptLogService.redact("gsk_abcdefghijklmnopqrst"))
                .isEqualTo("[redacted]");
    }

    @Test
    void leavesOrdinaryTextAlone() {
        String text = "Explain the difference between a mutex and a semaphore.";
        assertThat(PromptLogService.redact(text)).isEqualTo(text);
    }

    @Test
    void recordingIsANoOpWhenDisabled() {
        // repository is null: a call here would NPE if the disabled guard were missing
        service().record(1L, "req", "gpt-4o-mini", "{}", "{}");
    }
}
