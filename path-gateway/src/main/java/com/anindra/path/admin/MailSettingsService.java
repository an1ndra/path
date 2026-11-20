package com.anindra.path.admin;

import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.MailSettings;
import com.anindra.path.repo.MailSettingsRepository;
import com.anindra.path.secret.SecretCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Alert-email delivery, configurable from the console or the environment.
 *
 * <p>Precedence: an enabled row in {@code mail_settings} wins; otherwise the Spring Mail
 * environment ({@code SPRING_MAIL_*}) is used, which is what a config-file deployment
 * already does. The SMTP password is encrypted with the master key and is only ever
 * written, never read back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailSettingsService {

    private final MailSettingsRepository repository;
    private final SecretCipher cipher;
    private final GatewayProperties props;
    private final org.springframework.core.env.Environment environment;

    /** What the console shows: everything except the password. */
    public Map<String, Object> view() {
        MailSettings settings = repository.findById(MailSettings.SINGLETON).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", settings != null && settings.isEnabled());
        out.put("source", source());
        out.put("host", settings == null ? envHost() : settings.getHost());
        out.put("port", settings == null ? envPort() : settings.getPort());
        out.put("username", settings == null ? "" : nullToEmpty(settings.getUsername()));
        out.put("from", settings == null ? props.getAlerts().getEmailFrom() : settings.getFromAddress());
        out.put("to", settings == null ? props.getAlerts().getEmailTo() : settings.getToAddress());
        out.put("has_password", settings != null && settings.getPasswordCipher() != null);
        out.put("updated_at", settings == null || settings.getUpdatedAt() == null
                ? null : settings.getUpdatedAt().toString());
        return out;
    }

    private String source() {
        MailSettings settings = repository.findById(MailSettings.SINGLETON).orElse(null);
        if (settings != null && settings.isEnabled()) {
            return "console";
        }
        if (envHost() != null && !envHost().isBlank()) {
            return "environment";
        }
        return "none";
    }

    /** SPRING_MAIL_HOST: the config-file route, kept working alongside the console. */
    private String envHost() {
        return environment.getProperty("spring.mail.host", "");
    }

    private Integer envPort() {
        String port = environment.getProperty("spring.mail.port");
        return port == null || port.isBlank() ? null : Integer.valueOf(port);
    }

    @Transactional
    public Map<String, Object> save(String host, Integer port, String username, String password,
                                    String from, String to, Boolean enabled) {
        MailSettings settings = repository.findById(MailSettings.SINGLETON)
                .orElseGet(() -> {
                    MailSettings created = new MailSettings();
                    created.setId(MailSettings.SINGLETON);
                    return created;
                });

        boolean turnOn = enabled == null ? true : enabled;
        if (turnOn) {
            String effectiveHost = host == null || host.isBlank() ? settings.getHost() : host.trim();
            String effectiveTo = to == null || to.isBlank() ? settings.getToAddress() : to.trim();
            if (effectiveHost == null || effectiveHost.isBlank()) {
                throw ApiException.badRequest("'host' is required to enable email alerting.", "host");
            }
            if (effectiveTo == null || effectiveTo.isBlank()) {
                throw ApiException.badRequest("'to' must list at least one recipient.", "to");
            }
        }

        if (host != null) {
            settings.setHost(host.trim());
        }
        if (port != null) {
            if (port <= 0 || port > 65535) {
                throw ApiException.badRequest("'port' must be between 1 and 65535.", "port");
            }
            settings.setPort(port);
        }
        if (username != null) {
            settings.setUsername(username.isBlank() ? null : username.trim());
        }
        // Blank keeps the stored password, so an operator can change the host without
        // re-typing the secret; "clear" is explicit.
        if (password != null) {
            if (password.equals("-")) {
                settings.setPasswordCipher(null);
            } else if (!password.isBlank()) {
                settings.setPasswordCipher(cipher.encrypt(password));
            }
        }
        if (from != null && !from.isBlank()) {
            settings.setFromAddress(from.trim());
        }
        if (to != null) {
            settings.setToAddress(to.isBlank() ? null : to.trim());
        }
        settings.setEnabled(turnOn);
        settings.setUpdatedAt(Instant.now());
        repository.save(settings);
        return view();
    }

    /** Recipients for alerts, from the console row or the environment. */
    public String recipients() {
        MailSettings settings = repository.findById(MailSettings.SINGLETON).orElse(null);
        if (settings != null && settings.isEnabled() && settings.getToAddress() != null
                && !settings.getToAddress().isBlank()) {
            return settings.getToAddress();
        }
        return props.getAlerts().getEmailTo();
    }

    public String fromAddress() {
        MailSettings settings = repository.findById(MailSettings.SINGLETON).orElse(null);
        if (settings != null && settings.isEnabled() && settings.getFromAddress() != null
                && !settings.getFromAddress().isBlank()) {
            return settings.getFromAddress();
        }
        return props.getAlerts().getEmailFrom();
    }

    public boolean emailEnabled() {
        String to = recipients();
        return to != null && !to.isBlank() && sender().isPresent();
    }

    /** The sender to use: a console-configured one, or the Spring Mail environment. */
    public Optional<JavaMailSender> sender() {
        MailSettings settings = repository.findById(MailSettings.SINGLETON).orElse(null);
        if (settings != null && settings.isEnabled() && settings.getHost() != null
                && !settings.getHost().isBlank()) {
            return Optional.of(build(settings));
        }
        return Optional.ofNullable(envSender());
    }

    private JavaMailSender envSender() {
        MailSettings probe = new MailSettings();
        probe.setHost(envHost());
        probe.setPort(envPort());
        if (probe.getHost() == null || probe.getHost().isBlank()) {
            return null;
        }
        return build(probe);
    }

    private JavaMailSender build(MailSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.getHost());
        sender.setPort(settings.getPort() == null ? 587 : settings.getPort());
        if (settings.getUsername() != null && !settings.getUsername().isBlank()) {
            sender.setUsername(settings.getUsername());
            String password = settings.getPasswordCipher() == null
                    ? null : cipher.decrypt(settings.getPasswordCipher());
            sender.setPassword(password);
        }

        Properties mailProps = sender.getJavaMailProperties();
        mailProps.put("mail.transport.protocol", "smtp");
        mailProps.put("mail.smtp.auth", String.valueOf(
                settings.getUsername() != null && !settings.getUsername().isBlank()));
        mailProps.put("mail.smtp.starttls.enable", "true");
        mailProps.put("mail.smtp.connectiontimeout", "5000");
        mailProps.put("mail.smtp.timeout", "5000");
        mailProps.put("mail.smtp.writetimeout", "5000");
        return sender;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
