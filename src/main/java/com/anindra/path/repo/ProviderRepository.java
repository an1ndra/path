package com.anindra.path.repo;

import com.anindra.path.domain.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProviderRepository extends JpaRepository<Provider, Long> {

    Optional<Provider> findBySlug(String slug);

    List<Provider> findByEnabledTrueOrderBySlugAsc();

    List<Provider> findAllByOrderBySlugAsc();
}
