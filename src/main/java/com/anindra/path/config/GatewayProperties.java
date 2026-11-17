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
    private boolean bootstrapDemoKey = false;

    private int defaultRpmLimit = 120;

    /** Concurrent in-flight requests allowed per key unless the key overrides it. */
    private int defaultMaxConcurrentRequests = 8;

    /** Request bodies larger than this are rejected with 413. */
    private long maxRequestBytes = 1_048_576;

    /** Hard cap on max_tokens applied to every request; 0 disables. */
    private int maxOutputTokens = 0;

    /** How long to wait for in-flight streams during shutdown. */
    private Duration drainTimeout = Duration.ofSeconds(30);

    /** Usage rows older than this are deleted; zero keeps them forever. */
    private Duration usageRetention = Duration.ofDays(180);

    /** Null means unlimited unless set. */
    private BigDecimal defaultCreditLimitUsd;

    private Breaker breaker = new Breaker();
    private Idempotency idempotency = new Idempotency();
    private Cache cache = new Cache();
    private Guardrails guardrails = new Guardrails();
    private Alerts alerts = new Alerts();
    private PromptLogging promptLogging = new PromptLogging();
    private RateLimit rateLimit = new RateLimit();
    private AdminAuth adminAuth = new AdminAuth();
    private ProxyAuth proxyAuth = new ProxyAuth();

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
    public static class ProxyAuth {
        /** Trust an identity header injected by an authenticating reverse proxy (SSO at the edge). */
        private boolean enabled = false;
        /** Header the proxy sets, e.g. X-Forwarded-User or Cf-Access-Authenticated-User-Email. */
        private String header = "X-Forwarded-User";
        /** Scopes granted to a proxy-authenticated identity. */
        private String scopes = "read,write";
    }

    @Getter
    @Setter
    public static class AdminAuth {
        /** Failed admin auth attempts allowed per window before 429s start. */
        private int maxFailures = 10;
        private Duration window = Duration.ofMinutes(5);
    }

    @Getter
    @Setter
    public static class RateLimit {
        /** "memory" (single node) or "valkey" (shared across instances). */
        private String backend = "memory";
        private String prefix = "path";
    }

    @Getter
    @Setter
    public static class PromptLogging {
        /** Off by default: storing prompts creates a PII/secret store. */
        private boolean enabled = false;
        private Duration retention = Duration.ofDays(7);
        private int maxBodyBytes = 65_536;
    }

    @Getter
    @Setter
    public static class Alerts {
        /** Slack-compatible webhook; blank disables alerting. */
        private String webhookUrl = "";
        private Duration cooldown = Duration.ofHours(1);
        /** Warn when a key has less than this fraction of its credit left. */
        private double budgetThreshold = 0.8;
    }

    @Getter
    @Setter
    public static class Guardrails {
        /** Redact personal identifiers before a prompt is written to prompt_logs. */
        private boolean promptLogRedaction = true;

        /** Redact the prompt a provider receives. Off by default: it changes what the model sees. */
        private boolean redactBeforeSend = false;

        /** Call the provider's moderation endpoint before the completion. */
        private boolean moderation = false;

        private String moderationModel = "omni-moderation-latest";
    }

    @Getter
    @Setter
    public static class Cache {
        /** Responses larger than this are not cached. */
        private int maxBodyBytes = 2_097_152;

        /** Public model used to embed prompts for the semantic tier; blank disables it. */
        private String semanticModel = "";

        /** How many entries one key+model may hold before the oldest is dropped. */
        private int semanticMaxEntries = 200;
    }

    @Getter
    @Setter
    public static class Idempotency {
        private Duration ttl = Duration.ofHours(24);
        /** A PENDING claim older than this is treated as abandoned and reused. */
        private Duration pendingTimeout = Duration.ofMinutes(2);
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
        private ProviderKey gemini = new ProviderKey();
    }

    @Getter
    @Setter
    public static class ProviderKey {
        private String apiKey = "";
    }
}
