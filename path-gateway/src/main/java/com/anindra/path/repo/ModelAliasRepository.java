package com.anindra.path.repo;

import com.anindra.path.domain.ModelAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelAliasRepository extends JpaRepository<ModelAlias, Long> {

    Optional<ModelAlias> findByAlias(String alias);

    List<ModelAlias> findAllByOrderByAliasAsc();

    void deleteByAlias(String alias);
}
