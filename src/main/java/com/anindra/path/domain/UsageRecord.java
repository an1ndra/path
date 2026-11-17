package com.anindra.path.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "usage_records", indexes = {
        @Index(name = "ix_usage_created", columnList = "createdAt"),
        @Index(name = "ix_usage_client", columnList = "clientKeyId"),
        @Index(name = "ix_usage_model", columnList = "publicModelId")
})
@Getter
@Setter
@NoArgsConstructor
public class UsageRecord {

    public enum Status {SUCCESS, ERROR}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Long clientKeyId;

    @Column(length = 128)
    private String clientKeyName;

    /** Opaque identifier the caller supplied for its own end user; null when absent. */
    @Column(length = 128)
    private String endUser;

    @Column(length = 128)
    private String publicModelId;

    @Column(length = 64)
    private String providerSlug;

    @Column(length = 256)
    private String upstreamModelId;

    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    /** Prompt tokens served from the provider cache (a subset of promptTokens). */
    private int cachedInputTokens;

    /** Anthropic cache-write tokens (a subset of promptTokens). */
    private int cacheWriteTokens;

    private int reasoningTokens;
    private int audioInputTokens;
    private int audioOutputTokens;

    @Column(precision = 18, scale = 8)
    private BigDecimal inputCostUsd = BigDecimal.ZERO;

    @Column(precision = 18, scale = 8)
    private BigDecimal cachedInputCostUsd = BigDecimal.ZERO;

    @Column(precision = 18, scale = 8)
    private BigDecimal cacheWriteCostUsd = BigDecimal.ZERO;

    @Column(precision = 18, scale = 8)
    private BigDecimal outputCostUsd = BigDecimal.ZERO;

    /** What the cached tokens would have cost at the plain input rate, minus what they did cost. */
    @Column(precision = 18, scale = 8)
    private BigDecimal cachedSavingsUsd = BigDecimal.ZERO;

    @Column(precision = 18, scale = 8)
    private BigDecimal costUsd = BigDecimal.ZERO;

    /** True when the provider reported no usage block and the numbers were inferred. */
    private boolean usageEstimated;

    /** Time to first streamed token; null for non-streaming calls. */
    private Long ttftMs;

    @Column(precision = 10, scale = 2)
    private BigDecimal tokensPerSecond;

    @Column(length = 128)
    private String upstreamRequestId;

    @Column(length = 32)
    private String finishReason;

    /** 1 = first route worked; >1 means a fallback happened. */
    private int attempts;

    /** Provider that failed before the successful route, if any. */
    @Column(length = 64)
    private String fallbackFrom;

    private long latencyMs;

    private boolean streamed;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private Status status = Status.SUCCESS;

    @Column(length = 64)
    private String errorCode;

    @Column(length = 512)
    private String errorMessage;
}
