package com.anindra.path.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Opt-in request/response capture for support, redacted before storage. */
@Entity
@Table(name = "prompt_logs", indexes = @Index(name = "ix_prompt_log_created", columnList = "createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class PromptLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long clientKeyId;

    @Column(length = 64)
    private String requestId;

    @Column(length = 128)
    private String publicModelId;

    @Column(columnDefinition = "text")
    private String requestBody;

    @Column(columnDefinition = "text")
    private String responseBody;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant expiresAt;
}
