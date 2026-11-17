package com.anindra.path.repo;

import com.anindra.path.domain.AdminAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminAuditRepository extends JpaRepository<AdminAudit, Long> {

    List<AdminAudit> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AdminAudit> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);
}
