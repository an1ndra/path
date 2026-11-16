package com.anindra.path.domain;

import jakarta.persistence.*;
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
    @Column(nullable = false, length = 32)
    private ProviderType type;

    /** Usually ends at /v1, e.g. https://api.openai.com/v1 */
    @Column(nullable = false, length = 512)
    private String baseUrl;

    /** AES-GCM encrypted upstream credential. Null falls back to the environment. */
    @Column(length = 2048)
    private String apiKeyCipher;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Provider(String slug, String displayName, ProviderType type, String baseUrl) {
        this.slug = slug;
        this.displayName = displayName;
        this.type = type;
        this.baseUrl = baseUrl;
    }
}
