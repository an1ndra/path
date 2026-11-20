package com.anindra.path.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A scoped admin credential. The plaintext is shown once and stored hashed. */
@Entity
@Table(name = "admin_tokens")
@Getter
@Setter
@NoArgsConstructor
public class AdminToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, length = 24)
    private String tokenPrefix;

    @Column(nullable = false, length = 8)
    private String tokenSuffix;

    /** Comma-separated: "read", "write". Write implies read. */
    @Column(nullable = false, length = 32)
    private String scopes = "read";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
