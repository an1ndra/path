package com.anindra.path.admin;

import com.anindra.path.api.ApiException;
import com.anindra.path.repo.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tenancytest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key"
})
class TenancyServiceTest {

    @Autowired
    private TenancyService tenancy;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void clean() {
        projects.deleteAll();
    }

    @Test
    void createsProjectsAndRejectsDuplicates() {
        var created = tenancy.createProject("team-a", "Team A", null, null, null, null, null);
        assertThat(created.get("slug")).isEqualTo("team-a");

        assertThatThrownBy(() -> tenancy.createProject("team-a", "Again", null, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "project_exists");
    }

    @Test
    void tokensCarryScopes() {
        var readOnly = tenancy.createToken("read-only", "read");
        var readWrite = tenancy.createToken("full", "write");

        var readPrincipal = tenancy.authenticate(readOnly.plaintext()).orElseThrow();
        assertThat(readPrincipal.canWrite()).isFalse();

        var writePrincipal = tenancy.authenticate(readWrite.plaintext()).orElseThrow();
        assertThat(writePrincipal.canWrite()).isTrue();
    }

    @Test
    void tokensAreStoredHashed() {
        var created = tenancy.createToken("hashed", "read");

        assertThat(created.token().getTokenHash()).hasSize(64)
                .isNotEqualTo(created.plaintext());
        assertThat(created.token().getTokenPrefix()).isEqualTo(created.plaintext().substring(0, 12));
    }

    @Test
    void revokedTokensStopAuthenticating() {
        var created = tenancy.createToken("temp", "read");
        tenancy.revokeToken(created.token().getId());

        assertThat(tenancy.authenticate(created.plaintext())).isEmpty();
    }

    @Test
    void unknownTokensDoNotAuthenticate() {
        assertThat(tenancy.authenticate("adm_not-a-real-token")).isEmpty();
    }

    @Test
    void invalidScopesAreRejected() {
        assertThatThrownBy(() -> tenancy.createToken("bad", "admin"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "invalid_request");
    }
}
