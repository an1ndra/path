package com.anindra.path.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One way to serve a public model id: a public name mapped onto a concrete upstream
 * model at a concrete provider, with a price and a priority used for fallback ordering.
 */
@Entity
@Table(name = "model_routes",
        uniqueConstraints = @UniqueConstraint(name = "uk_route",
                columnNames = {"publicModelId", "providerId", "upstreamModelId"}),
        indexes = @Index(name = "ix_route_public", columnList = "publicModelId"))
@Getter
@Setter
@NoArgsConstructor
public class ModelRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String publicModelId;

    @Column(nullable = false)
    private Long providerId;

    @Column(nullable = false, length = 256)
    private String upstreamModelId;

    @Column(precision = 18, scale = 6)
    private BigDecimal inputPricePerMillion;

    @Column(precision = 18, scale = 6)
    private BigDecimal outputPricePerMillion;

    /** Price for tokens served from the provider's prompt cache. Falls back to the input price. */
    @Column(precision = 18, scale = 6)
    private BigDecimal cachedInputPricePerMillion;

    /** Anthropic-style cache-write price (typically 1.25x input). Falls back to the input price. */
    @Column(precision = 18, scale = 6)
    private BigDecimal cacheWritePricePerMillion;

    /** Lower runs first. */
    @Column(nullable = false)
    private int priority = 10;

    private Integer contextLength;

    @Column(nullable = false)
    private boolean supportsStreaming = true;

    @Column(nullable = false)
    private boolean supportsTools = true;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Opt-in: replay identical deterministic requests from the response cache. */
    /** Traffic share within a public model; equal weights split evenly. */
    @Column(nullable = false)
    private int weight = 100;

    /** priority | fastest | cheapest-viable */
    @Column(nullable = false, length = 32)
    private String strategy = "priority";

    /** For cheapest-viable: the p95 a route must beat to stay preferred. */
    private Integer latencySloMs;

    @Column(nullable = false)
    private boolean cacheEnabled = false;

    /** Second cache tier: a near-duplicate prompt may reuse an entry. Opt-in per route. */
    @Column(nullable = false)
    private boolean semanticCacheEnabled = false;

    /** Cosine similarity a candidate must reach. 0.95 is deliberately strict. */
    @Column(precision = 4, scale = 3, nullable = false)
    private java.math.BigDecimal semanticThreshold = new java.math.BigDecimal("0.950");

    @Column(nullable = false)
    private int cacheTtlSeconds = 3600;

    @Column(length = 512)
    private String description;

    public ModelRoute(String publicModelId, Long providerId, String upstreamModelId,
                      BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, int priority) {
        this.publicModelId = publicModelId;
        this.providerId = providerId;
        this.upstreamModelId = upstreamModelId;
        this.inputPricePerMillion = inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
        this.priority = priority;
    }
}
