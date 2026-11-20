package com.anindra.path.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One mutating admin action, for "who changed this and when". */
@Entity
@Table(name = "admin_audit", indexes = @Index(name = "ix_audit_created", columnList = "createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class AdminAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String actor;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 128)
    private String target;

    @Column(columnDefinition = "text")
    private String details;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
