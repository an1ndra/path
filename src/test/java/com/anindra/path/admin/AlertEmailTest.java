package com.anindra.path.admin;

import com.anindra.path.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AlertEmailTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final MailSettingsService mail = mock(MailSettingsService.class);
    private final AlertService alerts =
            new AlertService(new GatewayProperties(), new ObjectMapper(), mail);

    private void configure(String recipients) {
        when(mail.recipients()).thenReturn(recipients);
        when(mail.fromAddress()).thenReturn("path@example.com");
        when(mail.emailEnabled()).thenReturn(!recipients.isBlank());
        when(mail.sender()).thenReturn(Optional.of(sender));
    }

    @Test
    void sendsOneEmailPerAlert() {
        configure("ops@example.com");

        alerts.notify("key.budget_low", Map.of("key_id", 1, "remaining_usd", "0.42"));

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, timeout(2000)).send(message.capture());
        assertThat(message.getValue().getSubject()).isEqualTo("[Path] key.budget_low");
        assertThat(message.getValue().getTo()).containsExactly("ops@example.com");
        assertThat(message.getValue().getText()).contains("key.budget_low").contains("remaining_usd: 0.42");
    }

    @Test
    void multipleRecipientsAreSplit() {
        configure("a@example.com, b@example.com");

        alerts.notify("provider.circuit_open", Map.of("provider", "openai"));

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, timeout(2000)).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactlyInAnyOrder("a@example.com", "b@example.com");
    }

    @Test
    void noRecipientsMeansNoMail() {
        configure("");

        assertThat(alerts.emailEnabled()).isFalse();
        alerts.notify("anything", Map.of());

        verifyNoInteractions(sender);
        verify(mail, org.mockito.Mockito.never()).sender();
    }

    @Test
    void aMissingSenderIsANoOp() {
        when(mail.recipients()).thenReturn("ops@example.com");
        when(mail.emailEnabled()).thenReturn(false);
        when(mail.sender()).thenReturn(Optional.empty());

        alerts.notify("anything", Map.of());

        verifyNoInteractions(sender);
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

    @Test
    void mailboxFailuresDoNotEscape() {
        configure("ops@example.com");
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(sender).send(any(SimpleMailMessage.class));

        alerts.notify("provider.circuit_open", Map.of("provider", "openai"));

        verify(sender, timeout(2000)).send(any(SimpleMailMessage.class));
    }
}
