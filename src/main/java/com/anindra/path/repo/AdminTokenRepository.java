package com.anindra.path.repo;

import com.anindra.path.domain.AdminToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdminTokenRepository extends JpaRepository<AdminToken, Long> {

    Optional<AdminToken> findByTokenHash(String tokenHash);

    List<AdminToken> findAllByOrderByCreatedAtDesc();
}
