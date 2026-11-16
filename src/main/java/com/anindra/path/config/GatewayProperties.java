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

    /** Null means unlimited unless set. */
    private BigDecimal defaultCreditLimitUsd;

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
