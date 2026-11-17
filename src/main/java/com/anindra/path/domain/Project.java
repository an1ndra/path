package com.anindra.path.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** A tenant grouping for keys and usage. */
@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String slug;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(precision = 18, scale = 6)
    private BigDecimal creditLimitUsd;

    @Column(precision = 18, scale = 6)
    private BigDecimal dailyLimitUsd;

    @Column(precision = 18, scale = 6)
    private BigDecimal monthlyLimitUsd;

    /** Requests per minute across every key in the project; null means unlimited. */
    private Integer rpmLimit;

    /** In-flight requests across the project; null means unlimited. */
    private Integer maxConcurrentRequests;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Project(String slug, String name) {
        this.slug = slug;
        this.name = name;
    }
}
