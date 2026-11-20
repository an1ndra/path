package com.anindra.path.gateway;

import com.anindra.path.admin.AlertService;
import com.anindra.path.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ProviderErrorRateWatcherTest {

    private final RoutingMetrics routing = new RoutingMetrics();
    private final AlertService alerts = Mockito.mock(AlertService.class);
    private final GatewayProperties props = new GatewayProperties();
    private final ProviderErrorRateWatcher watcher =
            new ProviderErrorRateWatcher(routing, alerts, props);

    private void fail(String provider, String model, int times) {
        for (int i = 0; i < times; i++) {
            routing.recordFailure(provider, model);
        }
    }

    private void succeed(String provider, String model, int times) {
        for (int i = 0; i < times; i++) {
            routing.recordSuccess(provider, model, 120);
        }
    }

    @Test
    void firesOncePerProviderPerHourNoMatterHowManyRequestsFailed() {
        fail("openai", "gpt-4o", 200); // many requests, one alert

        watcher.check();
        watcher.check();
        watcher.check();

        verify(alerts, times(1)).notifyOnce(
                startsWith("provider.error_rate_high:openai:"),
                eq("provider.error_rate_high"), anyMap());
    }

    @Test
    void thePayloadCarriesTheRateAndTheSampleCount() {
        fail("openai", "gpt-4o", 25);

        watcher.check();

        verify(alerts).notifyOnce(any(), eq("provider.error_rate_high"),
                argThat(payload -> payload.get("provider").equals("openai")
                        && ((Number) payload.get("samples")).longValue() >= 25
                        && ((Double) payload.get("error_rate")) > 0.25));
    }

    @Test
    void doesNotFireBelowTheSampleMinimum() {
        fail("openai", "gpt-4o", 10); // 10 < default 20: a cold start is not an incident

        watcher.check();

        verifyNoInteractions(alerts);
    }

    @Test
    void oneAlertCoversEveryModelOfTheSameProvider() {
        fail("openai", "gpt-4o", 10);
        fail("openai", "gpt-4o-mini", 10);

        watcher.check();

        verify(alerts, times(1)).notifyOnce(
                startsWith("provider.error_rate_high:openai:"),
                eq("provider.error_rate_high"), anyMap());
    }

    @Test
    void recoveryFiresOnceWhenTheRateSettles() {
        fail("openai", "gpt-4o", 20);
        watcher.check();

        // 0.8^12 of the original 1.0 is ~0.07, under half the 0.25 threshold.
        succeed("openai", "gpt-4o", 12);
        watcher.check();
        watcher.check();

        verify(alerts, times(1)).notifyOnce(
                startsWith("provider.error_rate_normal:openai:"),
                eq("provider.error_rate_normal"), anyMap());
    }

    @Test
    void twoBadProvidersAreTwoAlerts() {
        fail("openai", "gpt-4o", 20);
        fail("anthropic", "claude", 20);

        watcher.check();

        verify(alerts, times(1)).notifyOnce(startsWith("provider.error_rate_high:openai:"),
                eq("provider.error_rate_high"), anyMap());
        verify(alerts, times(1)).notifyOnce(startsWith("provider.error_rate_high:anthropic:"),
                eq("provider.error_rate_high"), anyMap());
    }

    @Test
    void aDisabledThresholdTurnsTheCheckOff() {
        props.getAlert().setErrorRate(0);
        fail("openai", "gpt-4o", 50);

        watcher.check();

        verifyNoInteractions(alerts);
    }
}
