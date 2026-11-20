package com.anindra.path;

import com.anindra.path.domain.UsageRecord;
import com.anindra.path.repo.UsageRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The archive slice must be selectable, because retention deletes by the same dates. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:usageexport;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.admin-token=test-admin",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key"
})
@AutoConfigureMockMvc
class UsageExportRangeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UsageRecordRepository usage;

    @BeforeEach
    void seedTwoMonths() {
        usage.deleteAll();
        usage.save(record("january-model", Instant.parse("2026-01-15T10:00:00Z")));
        usage.save(record("march-model", Instant.parse("2026-03-15T10:00:00Z")));
    }

    private static UsageRecord record(String model, Instant at) {
        UsageRecord record = new UsageRecord();
        record.setCreatedAt(at);
        record.setPublicModelId(model);
        record.setProviderSlug("openai");
        record.setStatus(UsageRecord.Status.SUCCESS);
        return record;
    }

    @Test
    void fromAndToSelectExactlyTheSliceToArchive() throws Exception {
        mvc.perform(get("/admin/usage/export")
                        .header("X-Admin-Token", "test-admin")
                        .param("from", "2026-01-01")
                        .param("to", "2026-02-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("january-model")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("march-model"))));
    }

    @Test
    void anUnparseableDateIsA400NamingTheParameter() throws Exception {
        mvc.perform(get("/admin/usage/export")
                        .header("X-Admin-Token", "test-admin")
                        .param("from", "last-tuesday"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"param\":\"from\"")));
    }

    @Test
    void fromAfterToIsRejected() throws Exception {
        mvc.perform(get("/admin/usage/export")
                        .header("X-Admin-Token", "test-admin")
                        .param("from", "2026-03-01")
                        .param("to", "2026-02-01"))
                .andExpect(status().isBadRequest());
    }
}
