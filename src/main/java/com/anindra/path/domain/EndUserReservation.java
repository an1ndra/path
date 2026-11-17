package com.anindra.path.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** In-flight spend for one end user on one key, so concurrent calls cannot overshoot. */
@Entity
@Table(name = "end_user_reservations")
@Getter
@Setter
@NoArgsConstructor
public class EndUserReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long clientKeyId;

    @Column(nullable = false, length = 128)
    private String endUser;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal reservedUsd = BigDecimal.ZERO;

    private Instant updatedAt;
}
