package com.anindra.path.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** A stored exact-match response, replayable without calling the provider. */
@Entity
@Table(name = "response_cache_entries",
        indexes = @Index(name = "ix_cache_expiry", columnList = "expiresAt"))
@Getter
@Setter
@NoArgsConstructor
public class ResponseCacheEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String cacheKey;

    @Column(nullable = false)
    private Long clientKeyId;

    @Column(length = 128)
    private String publicModelId;

    @Column(length = 64)
    private String providerSlug;

    @Column(length = 128)
    private String contentType;

    @Column(columnDefinition = "text")
    private String body;

    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    /** What this response cost when it was generated; reported as savings on a hit. */
    @Column(precision = 18, scale = 8)
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant expiresAt;
}
