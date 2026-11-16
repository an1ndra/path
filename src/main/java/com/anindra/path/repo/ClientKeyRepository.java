package com.anindra.path.repo;

import com.anindra.path.domain.ClientKey;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ClientKeyRepository extends JpaRepository<ClientKey, Long> {

    Optional<ClientKey> findByKeyHash(String keyHash);

    List<ClientKey> findAllByOrderByCreatedAtDesc();

    long countByEnabledTrue();

    /** Row lock so concurrent charges on one key cannot lose an update. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from ClientKey k where k.id = :id")
    Optional<ClientKey> lockById(Long id);
}
