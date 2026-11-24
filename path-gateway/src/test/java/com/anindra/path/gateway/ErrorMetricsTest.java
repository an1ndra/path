package com.anindra.path.gateway;

import com.anindra.path.config.GatewayProperties;
import com.anindra.path.config.RequestIdFilter;
import com.anindra.path.domain.UsageRecord;
import com.anindra.path.repo.UsageRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class ErrorMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UsageService usage = new UsageService(
            Mockito.mock(UsageRecordRepository.class),
            Mockito.mock(ApiKeyService.class),
            registry,
            new GatewayProperties());

    @Test
    void aServerFailureAndAClientFailureLandInDifferentClasses() {
        usage.recordError(null, null, "openai", "gpt-4o", 12, false, 1, 500,
                "upstream_error", "the provider fell over");
        usage.recordError(null, null, "openai", "gpt-4o", 12, false, 1, 400,
                "invalid_request", "the client sent nonsense");

        assertThat(registry.counter("path.errors", "provider", "openai",
                "class", "5xx", "code", "upstream_error").count()).isEqualTo(1.0);
        assertThat(registry.counter("path.errors", "provider", "openai",
                "class", "4xx", "code", "invalid_request").count()).isEqualTo(1.0);
        // The 5xx rule divides the 5xx series by all requests; it must not see the 400.
        assertThat(registry.find("path.errors").tag("class", "5xx").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aMissingCodeIsUnknownRatherThanACardinalityBomb() {
        usage.recordError(null, null, "groq", "llama", 5, false, 1, 503, null, "no body");

        assertThat(registry.counter("path.errors", "provider", "groq",
                "class", "5xx", "code", "unknown").count()).isEqualTo(1.0);
    }

    @Test
    void anErrorRecordCarriesTheGatewayRequestId() {
        UsageRecordRepository repo = Mockito.mock(UsageRecordRepository.class);
        UsageService service = new UsageService(repo, Mockito.mock(ApiKeyService.class),
                registry, new GatewayProperties());
        MDC.put(RequestIdFilter.MDC_KEY, "req-123");
        try {
            service.recordError(null, null, "openai", "gpt-4o", 12, false, 1, 500,
                    "upstream_error", "the provider fell over");
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }

        ArgumentCaptor<UsageRecord> saved = ArgumentCaptor.forClass(UsageRecord.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getRequestId()).isEqualTo("req-123");
    }
}
