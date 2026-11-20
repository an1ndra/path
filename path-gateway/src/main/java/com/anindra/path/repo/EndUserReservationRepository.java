package com.anindra.path.repo;

import com.anindra.path.domain.EndUserReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface EndUserReservationRepository extends JpaRepository<EndUserReservation, Long> {

    Optional<EndUserReservation> findByClientKeyIdAndEndUser(Long clientKeyId, String endUser);

    /**
     * Atomically takes {@code amount} when the result still fits the headroom
     * (limit minus spend already reported). Returns 0 when it would not.
     */
    @Modifying
    @Query("update EndUserReservation r set r.reservedUsd = r.reservedUsd + :amount, r.updatedAt = :now "
            + "where r.clientKeyId = :keyId and r.endUser = :endUser "
            + "and r.reservedUsd + :amount <= :headroom")
    int reserve(Long keyId, String endUser, BigDecimal amount, BigDecimal headroom, Instant now);

    @Modifying
    @Query("update EndUserReservation r set r.reservedUsd = r.reservedUsd - :amount, r.updatedAt = :now "
            + "where r.clientKeyId = :keyId and r.endUser = :endUser and r.reservedUsd >= :amount")
    int release(Long keyId, String endUser, BigDecimal amount, Instant now);

    @Modifying
    @Query("update EndUserReservation r set r.reservedUsd = 0, r.updatedAt = :now "
            + "where r.clientKeyId = :keyId and r.endUser = :endUser")
    int clear(Long keyId, String endUser, Instant now);
}
