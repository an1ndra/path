package com.anindra.path.gateway;

import com.anindra.path.domain.EndUserReservation;
import com.anindra.path.repo.EndUserReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-end-user budget reservations, so two concurrent calls for the same customer cannot
 * both pass a check that only one of them fits.
 *
 * <p>Spend itself lives in {@code usage_records}; this table only holds what is in flight.
 */
@Service
@RequiredArgsConstructor
public class EndUserBudget {

    private final EndUserReservationRepository reservations;

    /** @return true when the amount was reserved; false when it would exceed the headroom. */
    @Transactional
    public boolean reserve(Long keyId, String endUser, BigDecimal amount, BigDecimal headroom) {
        if (amount == null || amount.signum() <= 0) {
            return true;
        }
        ensureRow(keyId, endUser);
        return reservations.reserve(keyId, endUser, amount, headroom, Instant.now()) > 0;
    }

    @Transactional
    public void release(Long keyId, String endUser, BigDecimal amount) {
        if (endUser == null || amount == null || amount.signum() <= 0) {
            return;
        }
        reservations.release(keyId, endUser, amount, Instant.now());
    }

    /** Test and admin helper: drop in-flight amounts for one end user. */
    @Transactional
    public void clear(Long keyId, String endUser) {
        reservations.clear(keyId, endUser, Instant.now());
    }

    private void ensureRow(Long keyId, String endUser) {
        if (reservations.findByClientKeyIdAndEndUser(keyId, endUser).isPresent()) {
            return;
        }
        EndUserReservation row = new EndUserReservation();
        row.setClientKeyId(keyId);
        row.setEndUser(endUser);
        row.setReservedUsd(BigDecimal.ZERO);
        row.setUpdatedAt(Instant.now());
        try {
            reservations.save(row);
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // Another request created it first; the row exists either way.
        }
    }
}
