package com.anindra.path;

import com.anindra.path.gateway.ApiKeyService;
import com.anindra.path.repo.ClientKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gwtest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.admin-token=test-admin",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key"
})
@AutoConfigureMockMvc
class GatewayApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private ClientKeyRepository clientKeys;

    private String key;

    @BeforeEach
    void setUp() {
        clientKeys.deleteAll();
        key = apiKeys.create("unit-test", null, 1000).plaintext();
    }

    @Test
    void catalogueIsPublicAndSeeded() throws Exception {
        mvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[?(@.id == 'gpt-4o-mini')]").exists())
                .andExpect(jsonPath("$.data[?(@.id == 'claude-3.5-haiku')]").exists());
    }

    @Test
    void modelsRequireNoKeyButChatDoes() throws Exception {
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("missing_api_key"));
    }

    @Test
    void badKeyIsRejected() throws Exception {
        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-gw-does-not-exist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    void unknownModelIs404() throws Exception {
        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"no-such-model","messages":[{"role":"user","content":"hi"}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));
    }

    @Test
    void invalidMessageRoleIs400() throws Exception {
        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"gpt-4o-mini","messages":[{"role":"wizard","content":"hi"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    @Test
    void oversizedBodyIsRejectedBeforeParsing() throws Exception {
        String big = "x".repeat(1_100_000);
        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\""
                                + big + "\"}]}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("payload_too_large"));
    }

    @Test
    void adminRequiresToken() throws Exception {
        mvc.perform(get("/admin/providers"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/providers").header("X-Admin-Token", "test-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == 'openai')]").exists());
    }

    @Test
    void creatingALimitedKeyStoresTheLimit() throws Exception {
        mvc.perform(post("/admin/keys")
                        .header("X-Admin-Token", "test-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"limited","credit_limit_usd":2.5,"rpm_limit":7}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credit_limit_usd").value(2.5))
                .andExpect(jsonPath("$.rpm_limit").value(7))
                .andExpect(jsonPath("$.key").isNotEmpty());
    }

    @Test
    void creditsEndpointReportsUnlimitedKeys() throws Exception {
        mvc.perform(get("/v1/credits").header("Authorization", "Bearer " + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlimited").value(true))
                .andExpect(jsonPath("$.used_usd").value(0));
    }
}
