package com.anindra.path.admin;

import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.repo.MailSettingsRepository;
import com.anindra.path.secret.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mailsettings;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key"
})
class MailSettingsTest {

    @Autowired
    private MailSettingsService mail;

    @Autowired
    private MailSettingsRepository repository;

    @Autowired
    private SecretCipher cipher;

    @Autowired
    private GatewayProperties props;

    @Autowired
    private Environment environment;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void savingSettingsEncryptsThePasswordAndNeverReturnsIt() {
        Map<String, Object> view = mail.save("smtp.example.com", 587, "user", "hunter2",
                "path@example.com", "ops@example.com", true);

        assertThat(view.get("host")).isEqualTo("smtp.example.com");
        assertThat(view.get("configured")).isEqualTo(true);
        assertThat(view.get("has_password")).isEqualTo(true);
        assertThat(view.toString()).doesNotContain("hunter2");
        assertThat(repository.findById(1L).orElseThrow().getPasswordCipher())
                .doesNotContain("hunter2")
                .startsWith("v1:");
    }

    @Test
    void aBlankPasswordKeepsTheStoredOne() {
        mail.save("smtp.example.com", 587, "user", "hunter2", "a@b.c", "ops@example.com", true);
        String before = repository.findById(1L).orElseThrow().getPasswordCipher();

        mail.save("smtp2.example.com", null, null, "", null, null, true);

        assertThat(repository.findById(1L).orElseThrow().getPasswordCipher()).isEqualTo(before);
        assertThat(mail.view().get("host")).isEqualTo("smtp2.example.com");
    }

    @Test
    void enablingRequiresHostAndRecipients() {
        assertThatThrownBy(() -> mail.save("", null, null, null, null, "ops@example.com", true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> mail.save("smtp.example.com", null, null, null, null, "", true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("recipient");
    }

    @Test
    void disablingTurnsAlertingOffWithoutLosingTheConfiguration() {
        mail.save("smtp.example.com", 587, null, null, "a@b.c", "ops@example.com", true);
        mail.save(null, null, null, null, null, null, false);

        assertThat(mail.emailEnabled()).isFalse();
        assertThat(mail.view().get("host")).isEqualTo("smtp.example.com");
    }

    @Test
    void theConsoleConfigurationWinsOverTheEnvironment() {
        // No row: the environment decides, as before this feature existed.
        assertThat(mail.view().get("source")).isNotEqualTo("console");

        mail.save("smtp.console.test", 2525, null, null, "path@example.com", "ops@example.com", true);

        assertThat(mail.view().get("source")).isEqualTo("console");
        assertThat(mail.recipients()).isEqualTo("ops@example.com");
        assertThat(mail.fromAddress()).isEqualTo("path@example.com");
        assertThat(environment.getProperty("spring.mail.host", "")).isEmpty();
        assertThat(props.getAlerts().getEmailTo()).isEmpty();
    }
}
