package com.anindra.path;

import com.anindra.path.admin.AdminReportingService;
import com.anindra.path.admin.TenancyService;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.domain.UsageRecord;
import com.anindra.path.gateway.ApiKeyService;
import com.anindra.path.repo.ClientKeyRepository;
import com.anindra.path.repo.UsageRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Semantic hits are read from the usage rows, so they survive a restart and are identical
 * on every replica. An in-process counter could do neither.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:semantichits;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.admin-token=test-admin",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key"
})
class SemanticHitStatsTest {

    @Autowired
    private UsageRecordRepository usage;

    @Autowired
    private AdminReportingService reporting;

    @Autowired
    private ClientKeyRepository keys;

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private TenancyService tenancy;

    private Long keyId;

    @BeforeEach
    void setUp() {
        usage.deleteAll();
        keys.deleteAll();
        keyId = apiKeys.create("semantic-stats", null, 1000).key().getId();
    }

    private void record(Long clientKeyId, String tier) {
        UsageRecord record = new UsageRecord();
        record.setClientKeyId(clientKeyId);
        record.setPublicModelId("gpt-4o-mini");
        record.setProviderSlug("openai");
        record.setStatus(UsageRecord.Status.SUCCESS);
        record.setFinishReason("cache");
        record.setCacheTier(tier);
        usage.save(record);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cache(Map<String, Object> stats) {
        return (Map<String, Object>) stats.get("cache");
    }

    @Test
    void semanticHitsComeFromTheWindowRatherThanMemory() {
        record(keyId, "semantic");
        record(keyId, "semantic");
        record(keyId, "exact");
        record(keyId, null);

        Map<String, Object> stats = reporting.stats("24h", null, null);

        assertThat(cache(stats).get("semantic_hits")).isEqualTo(2L);
        assertThat(cache(stats).get("exact_hits")).isEqualTo(1L);
    }

    @Test
    void aProjectCountsOnlyItsOwnSemanticHits() {
        Long projectId = ((Number) tenancy.createProject("semantic-project", "Semantic project",
                null, null, null, null, null).get("id")).longValue();
        ClientKey member = keys.findById(keyId).orElseThrow();
        member.setProjectId(projectId);
        keys.save(member);
        ClientKey other = apiKeys.create("other-project-key", null, 1000).key();
        record(member.getId(), "semantic");
        record(other.getId(), "semantic");
        record(other.getId(), "semantic");

        Map<String, Object> stats = reporting.stats("24h", projectId, null);

        assertThat(cache(stats).get("semantic_hits")).isEqualTo(1L);
        assertThat(cache(stats).get("exact_hits")).isEqualTo(0L);
    }

    @Test
    void anEmptyWindowReportsZeroRatherThanNothing() {
        Map<String, Object> stats = reporting.stats("24h", null, null);

        assertThat(cache(stats).get("semantic_hits")).isEqualTo(0L);
        assertThat(cache(stats).get("exact_hits")).isEqualTo(0L);
    }
}
