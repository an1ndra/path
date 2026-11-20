package com.anindra.path.repo;

import com.anindra.path.domain.ModelRoute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ModelRouteRepository extends JpaRepository<ModelRoute, Long> {

    List<ModelRoute> findByPublicModelIdAndEnabledTrueOrderByPriorityAscIdAsc(String publicModelId);

    List<ModelRoute> findByEnabledTrueOrderByPublicModelIdAscPriorityAsc();

    List<ModelRoute> findByPublicModelIdOrderByPriorityAsc(String publicModelId);

    Optional<ModelRoute> findByPublicModelIdAndProviderIdAndUpstreamModelId(
            String publicModelId, Long providerId, String upstreamModelId);

    List<ModelRoute> findByProviderId(Long providerId);

    @Query("select distinct r.publicModelId from ModelRoute r where r.enabled = true order by r.publicModelId")
    List<String> findDistinctEnabledPublicModelIds();

    void deleteByProviderId(Long providerId);
}
