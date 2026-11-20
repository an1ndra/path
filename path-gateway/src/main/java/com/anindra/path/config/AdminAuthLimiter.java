package com.anindra.path.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Slows down guessing of the admin token: too many failed attempts from one address
 * within the window return 429 instead of another comparison.
 *
 * The address must come from a trustworthy source — see {@code AuthFilter.clientAddress}.
 * A per-address cap is only as good as the address: behind a proxy the peer is an edge
 * node, and any client-supplied forwarding header can be rotated at will.
 */
@Component
public class AdminAuthLimiter {

    private final FailureWindow window;

    public AdminAuthLimiter(GatewayProperties props) {
        this.window = new FailureWindow(
                props.getAdminAuth().getMaxFailures(),
                props.getAdminAuth().getWindow(),
                props.getAdminAuth().getGlobalMaxFailures());
    }

    public long retryAfterSeconds(String address) {
        return window.retryAfterSeconds(address);
    }

    public void onFailure(String address) {
        window.onFailure(address);
    }

    public void onSuccess(String address) {
        window.onSuccess(address);
    }

    @Scheduled(fixedDelay = 300_000)
    void evictStale() {
        window.evictStale();
    }
}
