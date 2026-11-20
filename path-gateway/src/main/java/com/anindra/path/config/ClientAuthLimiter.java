package com.anindra.path.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Caps how many API keys one address may have rejected per window.
 *
 * Without it the public data plane is an unlimited credential oracle: every request does a
 * key lookup and answers 401, so a client can evaluate guesses as fast as the gateway will
 * serve them — measurable in the hundreds per second, and enough traffic to knock a proxy
 * over. The comparison is cheap, so the cost of the attack is close to zero; the limit is
 * what gives it a budget.
 */
@Component
public class ClientAuthLimiter {

    private final FailureWindow window;

    public ClientAuthLimiter(GatewayProperties props) {
        this.window = new FailureWindow(
                props.getClientAuth().getMaxFailures(),
                props.getClientAuth().getWindow(),
                props.getClientAuth().getGlobalMaxFailures());
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
