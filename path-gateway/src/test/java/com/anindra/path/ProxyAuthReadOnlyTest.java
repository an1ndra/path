package com.anindra.path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GATEWAY_PROXY_AUTH_SCOPES=read: the identity can look but not change anything. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:proxyauthreadonly;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.admin-token=test-admin",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key",
        "gateway.proxy-auth.enabled=true",
        "gateway.proxy-auth.scopes=read"
})
@AutoConfigureMockMvc
class ProxyAuthReadOnlyTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void reportsTheProxyIdentityAndItsScopes() throws Exception {
        mvc.perform(get("/admin/settings").header("X-Forwarded-User", "viewer@example.com"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.auth_mode").value("proxy"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.subject").value("viewer@example.com"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.scopes[0]").value("read"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.can_write").value(false));
    }

    @Test
    void canRead() throws Exception {
        mvc.perform(get("/admin/providers").header("X-Forwarded-User", "viewer@example.com"))
                .andExpect(status().isOk());
    }

    @Test
    void cannotWrite() throws Exception {
        mvc.perform(post("/admin/providers")
                        .header("X-Forwarded-User", "viewer@example.com")
                        .contentType("application/json")
                        .content("{\"slug\":\"nope\",\"type\":\"openai\",\"base_url\":\"https://example.test/v1\"}"))
                .andExpect(status().isForbidden());
    }
}
