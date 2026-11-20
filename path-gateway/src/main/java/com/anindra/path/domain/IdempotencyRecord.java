package com.anindra.path.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One idempotency claim. A PENDING row exists for the duration of the upstream call;
 * it becomes COMPLETED with the replayable response, or is deleted on failure.
 */
@Entity
@Table(name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency",
                columnNames = {"clientKeyId", "idempotencyKey"}),
        indexes = @Index(name = "ix_idempotency_expiry", columnList = "expiresAt"))
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord {

    public enum Status {PENDING, COMPLETED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long clientKeyId;

    /** SHA-256 of the Idempotency-Key header. */
    @Column(nullable = false, length = 64)
    private String idempotencyKey;

    /** SHA-256 of the request body, to detect key reuse with a different payload. */
    @Column(nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    private int httpStatus;

    @Column(length = 128)
    private String contentType;

    @Column(columnDefinition = "text")
    private String body;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant expiresAt;
}
