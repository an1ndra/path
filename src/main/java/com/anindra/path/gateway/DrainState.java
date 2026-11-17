package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stops accepting work on shutdown and waits for in-flight streams to finish.
 *
 * Without this, Spring's 20s graceful-shutdown window cuts any generation still
 * running; with it, the readiness probe goes DOWN immediately and new requests are
 * refused while existing streams drain for up to {@code gateway.drain-timeout}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DrainState {

    private final GatewayProperties props;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicInteger inFlight = new AtomicInteger(0);

    public boolean isDraining() {
        return draining.get();
    }

    public int inFlight() {
        return inFlight.get();
    }

    /** Registers a request; refuses new work once draining has begun. */
    public void begin() {
        if (draining.get()) {
            throw new ApiException(503, "server_error", "shutting_down",
                    "The gateway is shutting down. Retry against another instance.");
        }
        inFlight.incrementAndGet();
    }

    public void end() {
        inFlight.decrementAndGet();
    }

    @EventListener(ContextClosedEvent.class)
    public void drain() {
        draining.set(true);
        long deadline = System.currentTimeMillis() + props.getDrainTimeout().toMillis();
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Shutdown drain finished with {} request(s) still in flight", inFlight.get());
    }
}
