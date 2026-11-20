package com.anindra.path.repo;

import com.anindra.path.domain.PromptLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PromptLogRepository extends JpaRepository<PromptLog, Long> {

    List<PromptLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<PromptLog> findByClientKeyIdOrderByCreatedAtDesc(Long clientKeyId, Pageable pageable);

    long deleteByExpiresAtBefore(Instant cutoff);
}
