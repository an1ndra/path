package com.anindra.path.admin;

import com.anindra.path.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceTest {

    private HttpServer server;
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger deliveries = new AtomicInteger();
    private final GatewayProperties props = new GatewayProperties();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    /** Given a 1-based attempt number, choose the status to answer with. */
    private IntFunction<Integer> statusForAttempt = attempt -> 200;
    private AlertService alerts;

    @BeforeEach
    void startWebhook() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", this::handle);
        server.start();
        props.getAlerts().setWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        props.getAlerts().setCooldown(java.time.Duration.ZERO);
        alerts = new AlertService(props, new ObjectMapper(),
                org.mockito.Mockito.mock(MailSettingsService.class), metrics);
    }

    private void handle(HttpExchange exchange) throws IOException {
        int attempt = attempts.incrementAndGet();
        int status = statusForAttempt.apply(attempt);
        if (status == 429) {
            exchange.getResponseHeaders().add("Retry-After", "0");
        }
        if (status >= 200 && status < 300) {
            deliveries.incrementAndGet();
        }
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void notifyOnceDeliversOneEventPerKey() throws Exception {
        for (int i = 0; i < 5; i++) {
            alerts.notifyOnce("project.budget_low:1:daily@2026-11-17", "project.budget_low",
                    Map.of("project", "acme"));
        }
        // A different period is a different alert.
        alerts.notifyOnce("project.budget_low:1:daily@2026-11-18", "project.budget_low",
                Map.of("project", "acme"));

        assertThat(awaitDeliveries(2)).isEqualTo(2);
    }

    @Test
    void noWebhookConfiguredIsANoOp() {
        props.getAlerts().setWebhookUrl("");
        alerts.notifyOnce("k", "event", Map.of());

        assertThat(deliveries.get()).isZero();
        assertThat(attempts.get()).isZero();
    }

    @Test
    void retriesA500ThenDeliversExactlyOnce() throws Exception {
        statusForAttempt = attempt -> attempt == 1 ? 500 : 200;

        alerts.notify("provider.circuit_open", Map.of("provider", "openai"));

        assertThat(awaitDeliveries(1)).isEqualTo(1);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(counter("delivered")).isEqualTo(1.0);
        assertThat(counter("retried")).isEqualTo(1.0);
        assertThat(counter("dropped")).isZero();
    }

    @Test
    void doesNotRetryA400() throws Exception {
        statusForAttempt = attempt -> 400;

        alerts.notify("provider.circuit_open", Map.of("provider", "openai"));

        assertThat(awaitAttempts(1)).isEqualTo(1);
        // Give an incorrect retry time to show up before asserting the count.
        Thread.sleep(300);
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(deliveries.get()).isZero();
        assertThat(counter("dropped")).isEqualTo(1.0);
        assertThat(counter("retried")).isZero();
    }

    @Test
    void honoursRetryAfterOn429() throws Exception {
        statusForAttempt = attempt -> attempt == 1 ? 429 : 200;

        alerts.notify("key.budget_low", Map.of("key_id", 1));

        assertThat(awaitDeliveries(1)).isEqualTo(1);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(counter("delivered")).isEqualTo(1.0);
    }

    @Test
    void givesUpAfterThreeAttemptsAndCountsOneDrop() throws Exception {
        statusForAttempt = attempt -> 503;

        alerts.notify("provider.circuit_open", Map.of("provider", "openai"));

        assertThat(awaitAttempts(3)).isEqualTo(3);
        Thread.sleep(300);
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(counter("dropped")).isEqualTo(1.0);
        assertThat(counter("retried")).isEqualTo(2.0);
    }

    @Test
    void aDeadEndpointCannotGrowTheQueueWithoutBound() throws Exception {
        // One worker, one queued task: anything beyond that must be dropped, not block.
        alerts = new AlertService(props, new ObjectMapper(),
                org.mockito.Mockito.mock(MailSettingsService.class), metrics, 1, 1);
        CountDownLatch release = new CountDownLatch(1);
        // Block the only worker by making the first response slow.
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            deliveries.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        props.getAlerts().setWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");

        for (int i = 0; i < 5; i++) {
            alerts.notify("event." + i, Map.of("i", i));
        }
        release.countDown();

        assertThat(counter("dropped")).isGreaterThanOrEqualTo(2.0);
        assertThat(awaitDeliveries(1)).isGreaterThanOrEqualTo(1);
    }

    private double counter(String outcome) {
        return metrics.counter("path.alerts.sent", "outcome", outcome).count();
    }

    private int awaitDeliveries(int expected) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            if (deliveries.get() >= expected) {
                // give an over-delivery a chance to show up
                Thread.sleep(150);
                return deliveries.get();
            }
            Thread.sleep(50);
        }
        return deliveries.get();
    }

    private int awaitAttempts(int expected) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            if (attempts.get() >= expected) {
                return attempts.get();
            }
            Thread.sleep(50);
        }
        return attempts.get();
    }
}
