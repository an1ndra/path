package com.anindra.path.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "providers")
@Getter
@Setter
@NoArgsConstructor
public class Provider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String slug;

    @Column(nullable = false, length = 128)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private ProviderType type;

    /** Usually ends at /v1, e.g. https://api.openai.com/v1 */
    @Column(nullable = false, length = 512)
    private String baseUrl;

    /**
     * When set, this provider is an Azure OpenAI resource: the deployment name goes in the
     * path, this value goes in {@code ?api-version}, and auth uses the {@code api-key}
     * header. Null means a plain OpenAI-compatible endpoint.
     */
    @Column(length = 32)
    private String apiVersion;

    /** AES-GCM encrypted upstream credential. Null falls back to the environment. */
    @Column(length = 4096)
    private String apiKeyCipher;

    /** Vertex only: the GCP project that owns the model. */
    @Column(length = 128)
    private String project;

    /** Vertex only: the region, e.g. us-central1. */
    @Column(length = 64)
    private String location;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Null falls back to the gateway default. */
    private Integer requestTimeoutMs;

    private Integer streamIdleTimeoutMs;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Provider(String slug, String displayName, ProviderType type, String baseUrl) {
        this.slug = slug;
        this.displayName = displayName;
        this.type = type;
        this.baseUrl = baseUrl;
    }
}
