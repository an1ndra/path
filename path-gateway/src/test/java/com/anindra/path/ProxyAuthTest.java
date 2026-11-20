package com.anindra.path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSO at the edge: the proxy authenticates the human and injects an identity header. The
 * gateway must then (a) refuse everything without that header, (b) attribute writes to the
 * identity, and (c) not require the pasted admin token at all.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:proxyauth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.admin-token=test-admin",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key",
        "gateway.proxy-auth.enabled=true",
        "gateway.proxy-auth.header=X-Forwarded-User"
})
@AutoConfigureMockMvc
class ProxyAuthTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void adminIsClosedWithoutTheProxyHeader() throws Exception {
        mvc.perform(get("/admin/providers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("proxy_identity_missing"));
    }

    @Test
    void thePastedTokenIsNotEnoughWhenProxyAuthIsOn() throws Exception {
        mvc.perform(get("/admin/providers").header("X-Admin-Token", "test-admin"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("proxy_identity_missing"));
    }

    @Test
    void aSignedInIdentityGetsIn() throws Exception {
        mvc.perform(get("/admin/settings").header("X-Forwarded-User", "anindra@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth_mode").value("proxy"));
    }

    @Test
    void writesAreAuditedAgainstTheIdentity() throws Exception {
        mvc.perform(post("/admin/providers")
                        .header("X-Forwarded-User", "anindra@example.com")
                        .contentType("application/json")
                        .content("{\"slug\":\"sso-test\",\"type\":\"openai\",\"base_url\":\"https://example.test/v1\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/admin/audit").header("X-Forwarded-User", "anindra@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actor").value("anindra@example.com"));
    }

}
