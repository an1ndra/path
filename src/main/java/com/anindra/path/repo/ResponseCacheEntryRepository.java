package com.anindra.path.repo;

import com.anindra.path.domain.ResponseCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface ResponseCacheEntryRepository extends JpaRepository<ResponseCacheEntry, Long> {

    Optional<ResponseCacheEntry> findByCacheKey(String cacheKey);

    long deleteByExpiresAtBefore(Instant cutoff);

    long deleteByClientKeyId(Long clientKeyId);
}
