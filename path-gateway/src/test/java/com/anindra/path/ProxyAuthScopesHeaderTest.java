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
 * Per-identity SSO scopes: the proxy sends both the user and a scope header. The header
 * is authoritative, so a viewer can read and an admin can write without sharing one
 * global scope set.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:proxyauthscopes;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.admin-token=test-admin",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key",
        "gateway.proxy-auth.enabled=true",
        "gateway.proxy-auth.header=X-Forwarded-User",
        "gateway.proxy-auth.scopes=read,write",
        "gateway.proxy-auth.scopes-header=X-Forwarded-Scopes"
})
@AutoConfigureMockMvc
class ProxyAuthScopesHeaderTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void missingScopeHeaderFailsClosed() throws Exception {
        mvc.perform(get("/admin/providers").header("X-Forwarded-User", "viewer@example.com"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("proxy_scope_missing"));
    }

    @Test
    void readScopeCanReadButCannotWrite() throws Exception {
        mvc.perform(get("/admin/settings")
                        .header("X-Forwarded-User", "viewer@example.com")
                        .header("X-Forwarded-Scopes", "read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopes[0]").value("read"))
                .andExpect(jsonPath("$.can_write").value(false));

        mvc.perform(get("/admin/providers")
                        .header("X-Forwarded-User", "viewer@example.com")
                        .header("X-Forwarded-Scopes", "read"))
                .andExpect(status().isOk());

        mvc.perform(post("/admin/providers")
                        .header("X-Forwarded-User", "viewer@example.com")
                        .header("X-Forwarded-Scopes", "read")
                        .contentType("application/json")
                        .content("{\"slug\":\"viewer-nope\",\"type\":\"openai\",\"base_url\":\"https://example.test/v1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeScopeCanWrite() throws Exception {
        mvc.perform(get("/admin/settings")
                        .header("X-Forwarded-User", "admin@example.com")
                        .header("X-Forwarded-Scopes", "read,write"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.can_write").value(true));

        mvc.perform(post("/admin/providers")
                        .header("X-Forwarded-User", "admin@example.com")
                        .header("X-Forwarded-Scopes", "read,write")
                        .contentType("application/json")
                        .content("{\"slug\":\"admin-ok\",\"type\":\"openai\",\"base_url\":\"https://example.test/v1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void unsupportedScopeHeaderFailsClosed() throws Exception {
        mvc.perform(get("/admin/providers")
                        .header("X-Forwarded-User", "viewer@example.com")
                        .header("X-Forwarded-Scopes", "admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("proxy_scope_invalid"));
    }
}
