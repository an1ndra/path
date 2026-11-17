package com.anindra.path.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * All tunables for the gateway. Every field has a sane default so the app boots with
 * an empty configuration file.
 */
@ConfigurationProperties(prefix = "gateway")
@Getter
@Setter
public class GatewayProperties {

    /** Shared secret for /admin/**. Empty disables the admin API entirely. */
    private String adminToken = "";

    /** When true, GET /v1/models works without an API key. */
    private boolean publicModels = true;

    /** Comma separated origin patterns, or * for any. */
    private String corsOrigins = "*";

    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(180);
    private Duration streamIdleTimeout = Duration.ofSeconds(120);

    /** Base64 encoded 32-byte AES key. Generated on first boot when empty. */
    private String masterKey = "";
    private String masterKeyPath = "./data/master.key";

    /** Create a demo client key on boot when the key table is empty. */
    private boolean bootstrapDemoKey = true;

    private int defaultRpmLimit = 120;

    /** Concurrent in-flight requests allowed per key unless the key overrides it. */
    private int defaultMaxConcurrentRequests = 8;

    /** Request bodies larger than this are rejected with 413. */
    private long maxRequestBytes = 1_048_576;

    /** Null means unlimited unless set. */
    private BigDecimal defaultCreditLimitUsd;

    private Breaker breaker = new Breaker();
    private Idempotency idempotency = new Idempotency();

    @Getter
    @Setter
    public static class Breaker {
        /** Consecutive failures before a provider is parked. */
        private int failureThreshold = 5;
        /** How long a provider stays parked after opening. */
        private Duration openDuration = Duration.ofSeconds(30);
        /** Routes tried per request, including the first. */
        private int maxAttempts = 3;
    }

    @Getter
    @Setter
    public static class Idempotency {
        private Duration ttl = Duration.ofHours(24);
        /** Responses larger than this are not stored for replay. */
        private int maxResponseBytes = 2_097_152;
    }

    private Providers providers = new Providers();

    @Getter
    @Setter
    public static class Providers {
        private ProviderKey openai = new ProviderKey();
        private ProviderKey anthropic = new ProviderKey();
        private ProviderKey groq = new ProviderKey();
    }

    @Getter
    @Setter
    public static class ProviderKey {
        private String apiKey = "";
    }
}
