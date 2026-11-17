package com.anindra.path.admin;

import com.anindra.path.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AlertServiceTest {

    @Test
    void isANoOpWithoutAWebhook() {
        GatewayProperties props = new GatewayProperties();
        AlertService alerts = new AlertService(props, new ObjectMapper());
        assertThatCode(() -> alerts.notify("test", Map.of("k", "v"))).doesNotThrowAnyException();
    }

    @Test
    void postsOncePerCooldownAndOncePerEvent() throws Exception {
        AtomicInteger received = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            received.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            GatewayProperties props = new GatewayProperties();
            props.getAlerts().setCooldown(Duration.ofMinutes(5));
            props.getAlerts().setWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");

            AlertService alerts = new AlertService(props, new ObjectMapper());
            alerts.notify("budget.low", Map.of("key", "a"));
            alerts.notify("budget.low", Map.of("key", "a"));
            alerts.notify("provider.down", Map.of("provider", "b"));

            long deadline = System.currentTimeMillis() + 3000;
            while (received.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            Thread.sleep(200);
            assertThat(received.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }
}
