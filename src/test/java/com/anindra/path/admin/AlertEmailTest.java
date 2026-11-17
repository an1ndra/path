package com.anindra.path.admin;

import com.anindra.path.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AlertEmailTest {

    private AlertService service(GatewayProperties props, JavaMailSender sender) {
        var beans = new StaticListableBeanFactory();
        if (sender != null) {
            beans.addBean("mailSender", sender);
        }
        return new AlertService(props, new ObjectMapper(), beans.getBeanProvider(JavaMailSender.class));
    }

    private GatewayProperties props(String to) {
        GatewayProperties props = new GatewayProperties();
        props.getAlerts().setEmailTo(to);
        props.getAlerts().setEmailFrom("path@example.com");
        return props;
    }

    @Test
    void sendsOneEmailPerAlert() {
        JavaMailSender sender = mock(JavaMailSender.class);
        AlertService service = service(props("ops@example.com"), sender);

        service.notify("key.budget_low", Map.of("key_id", 1, "remaining_usd", "0.42"));

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, timeout(2000)).send(message.capture());
        assertThat(message.getValue().getSubject()).isEqualTo("[Path] key.budget_low");
        assertThat(message.getValue().getTo()).containsExactly("ops@example.com");
        assertThat(message.getValue().getText())
                .contains("key.budget_low")
                .contains("remaining_usd: 0.42");
    }

    @Test
    void multipleRecipientsAreSplit() {
        JavaMailSender sender = mock(JavaMailSender.class);
        AlertService service = service(props("a@example.com, b@example.com"), sender);

        service.notify("provider.circuit_open", Map.of("provider", "openai"));

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, timeout(2000)).send(message.capture());
        assertThat(message.getValue().getTo())
                .containsExactlyInAnyOrder("a@example.com", "b@example.com");
    }

    @Test
    void noRecipientsMeansNoMail() {
        JavaMailSender sender = mock(JavaMailSender.class);
        AlertService service = service(props(""), sender);

        assertThat(service.emailEnabled()).isFalse();
        service.notify("anything", Map.of());

        verifyNoInteractions(sender);
    }

    @Test
    void recipientsWithoutAMailClientIsANoOp() {
        // GATEWAY_ALERT_EMAIL_TO set but spring.mail.host missing: must not throw at startup
        // or on the request path.
        AlertService service = service(props("ops@example.com"), null);

        assertThat(service.emailEnabled()).isFalse();
        service.notify("anything", Map.of());
    }

    @Test
    void bodyListsEveryField() {
        String text = AlertService.text(Map.of(
                "source", "path",
                "event", "project.budget_exhausted",
                "at", "2026-11-17T10:00:00Z",
                "project", "acme",
                "spent_usd", "10.00"));

        assertThat(text).contains("project.budget_exhausted").contains("project: acme").contains("spent_usd: 10.00");
        assertThat(text).doesNotContain("source: path");
    }
}
