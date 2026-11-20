package com.anindra.path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catalogue is gated by default; an operator can make it keyless again. Both directions
 * need coverage, because flipping the default is exactly the kind of change that regresses
 * silently.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:publicmodels;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.admin-token=test-admin",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key",
        "gateway.public-models=true"
})
@AutoConfigureMockMvc
class PublicModelsTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void catalogueIsKeylessWhenExplicitlyEnabled() throws Exception {
        mvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"));
    }
}
