package com.anindra.path.repo;

import com.anindra.path.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByClientKeyIdAndIdempotencyKey(Long clientKeyId, String idempotencyKey);

    long deleteByExpiresAtBefore(Instant cutoff);
}
