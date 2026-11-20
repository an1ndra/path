package com.anindra.path.repo;

import com.anindra.path.domain.ClientKey;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ClientKeyRepository extends JpaRepository<ClientKey, Long> {

    Optional<ClientKey> findByKeyHash(String keyHash);

    List<ClientKey> findAllByOrderByCreatedAtDesc();

    List<ClientKey> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    long countByEnabledTrue();

    /**
     * Atomically reserves budget for an in-flight request. The WHERE clause makes the
     * check-and-increment one statement, so concurrent requests cannot overshoot the limit.
     */
    @Modifying(clearAutomatically = true)
    @Query("update ClientKey k set k.reservedUsd = coalesce(k.reservedUsd, 0) + :amount, "
            + "k.reservedUpdatedAt = :now "
            + "where k.id = :id and (k.creditLimitUsd is null "
            + "or k.usedUsd + coalesce(k.reservedUsd, 0) + :amount <= k.creditLimitUsd)")
    int reserve(Long id, BigDecimal amount, Instant now);

    @Modifying(clearAutomatically = true)
    @Query("update ClientKey k set k.reservedUsd = case when coalesce(k.reservedUsd, 0) < :amount "
            + "then 0 else k.reservedUsd - :amount end where k.id = :id")
    int release(Long id, BigDecimal amount);

    @Modifying(clearAutomatically = true)
    @Query("update ClientKey k set k.reservedUsd = 0 where k.reservedUpdatedAt < :cutoff")
    int releaseStale(Instant cutoff);

    /** Row lock so concurrent charges on one key cannot lose an update. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from ClientKey k where k.id = :id")
    Optional<ClientKey> lockById(Long id);
}
