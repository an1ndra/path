package com.anindra.path.gateway;

import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.anindra.path.repo.ProviderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderHealthIndicatorTest {

    private final ProviderRepository providers = mock(ProviderRepository.class);
    private final ProviderCircuitBreaker breaker = mock(ProviderCircuitBreaker.class);

    private Provider provider(long id, String slug) {
        Provider provider = new Provider(slug, slug, ProviderType.OPENAI, "http://localhost");
        provider.setId(id);
        return provider;
    }

    @Test
    void staysUpWhileFallbackExists() {
        when(providers.findByEnabledTrueOrderBySlugAsc())
                .thenReturn(List.of(provider(1, "openai"), provider(2, "groq")));
        when(breaker.state(1L)).thenReturn(ProviderCircuitBreaker.State.OPEN);
        when(breaker.state(2L)).thenReturn(ProviderCircuitBreaker.State.CLOSED);

        var health = new ProviderHealthIndicator(providers, breaker).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("open_circuits")).isEqualTo(1);
    }

    @Test
    void reportsOutOfServiceWhenEveryProviderIsParked() {
        when(providers.findByEnabledTrueOrderBySlugAsc()).thenReturn(List.of(provider(1, "openai")));
        when(breaker.state(1L)).thenReturn(ProviderCircuitBreaker.State.OPEN);

        var health = new ProviderHealthIndicator(providers, breaker).health();

        assertThat(health.getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
    }
}
