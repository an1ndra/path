package com.anindra.path.admin;

import com.anindra.path.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceTest {

    private HttpServer server;
    private final AtomicInteger deliveries = new AtomicInteger();
    private final GatewayProperties props = new GatewayProperties();
    private AlertService alerts;

    @BeforeEach
    void startWebhook() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            deliveries.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        props.getAlerts().setWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        props.getAlerts().setCooldown(java.time.Duration.ZERO);
        alerts = new AlertService(props, new ObjectMapper());
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
    }

    private int awaitDeliveries(int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (deliveries.get() >= expected) {
                // give an over-delivery a chance to show up
                Thread.sleep(150);
                return deliveries.get();
            }
            Thread.sleep(50);
        }
        return deliveries.get();
    }
}
