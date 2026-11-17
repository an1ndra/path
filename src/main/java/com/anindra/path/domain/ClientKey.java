package com.anindra.path.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "client_keys", indexes = @Index(name = "ix_key_hash", columnList = "keyHash", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class ClientKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** SHA-256 hex of the full key. The plaintext is never stored. */
    @Column(nullable = false, unique = true, length = 64)
    private String keyHash;

    /** First characters of the key, shown in listings. */
    @Column(nullable = false, length = 24)
    private String keyPrefix;

    @Column(nullable = false, length = 8)
    private String keySuffix;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Null = unlimited. */
    @Column(precision = 18, scale = 6)
    private BigDecimal creditLimitUsd;

    @Column(precision = 18, scale = 6)
    private BigDecimal dailyLimitUsd;

    @Column(precision = 18, scale = 6)
    private BigDecimal monthlyLimitUsd;

    /** Clamps max_tokens on every request; null leaves the client's value alone. */
    private Integer maxOutputTokens;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal usedUsd = BigDecimal.ZERO;

    /** Pre-authorised worst-case spend for in-flight requests. */
    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal reservedUsd = BigDecimal.ZERO;

    private Instant reservedUpdatedAt;

    @Column(nullable = false)
    private int rpmLimit = 120;

    /** Null means use the gateway default. */
    private Integer maxConcurrentRequests;

    /** Owning project; null means unassigned. */
    private Long projectId;

    /** Comma-separated model ids or globs (`gpt-*`); blank means every model. */
    @Column(length = 1024)
    private String allowedModels;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant lastUsedAt;

    private Instant revokedAt;
}
