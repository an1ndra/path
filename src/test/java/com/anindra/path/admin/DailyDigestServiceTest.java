package com.anindra.path.admin;

import com.anindra.path.config.GatewayProperties;
import com.anindra.path.gateway.ProviderCircuitBreaker;
import com.anindra.path.repo.ProviderRepository;
import com.anindra.path.repo.UsageRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DailyDigestServiceTest {

    private final UsageRecordRepository usage = Mockito.mock(UsageRecordRepository.class);
    private final MailSettingsService mail = Mockito.mock(MailSettingsService.class);
    private final ProviderCircuitBreaker breaker = Mockito.mock(ProviderCircuitBreaker.class);
    private final ProviderRepository providers = Mockito.mock(ProviderRepository.class);
    private final GatewayProperties props = new GatewayProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private JavaMailSender sender;
    private final DailyDigestService digest = new DailyDigestService(
            usage, mail, props, breaker, providers, registry);

    private static DailyDigestService.Day day(long requests, long failures, String cost,
                                              long tokens, long prompt, long cached, String savings,
                                              String topError, long topErrorCount,
                                              String slowest, long slowestP95,
                                              List<DailyDigestService.ModelSpend> models) {
        return new DailyDigestService.Day(requests, failures, new BigDecimal(cost), tokens,
                prompt, cached, new BigDecimal(savings), topError, topErrorCount,
                slowest, slowestP95, models);
    }

    @Test
    void theRenderedDigestMatchesTheDocumentedShape() {
        DailyDigestService.Day today = day(12_431, 149, "18.42", 4_100_000, 1_000, 380, "2.90",
                "upstream_timeout", 120, "claude-sonnet-4.5", 3_400,
                List.of(new DailyDigestService.ModelSpend("gpt-4o", new BigDecimal("9.10")),
                        new DailyDigestService.ModelSpend("claude-sonnet-4.5", new BigDecimal("7.80"))));
        DailyDigestService.Day yesterday = day(11_500, 90, "17.32", 3_800_000, 900, 300, "2.10",
                "upstream_timeout", 70, "gpt-4o", 3_100,
                List.of(new DailyDigestService.ModelSpend("gpt-4o", new BigDecimal("8.00"))));

        assertThat(DailyDigestService.subject(today))
                .isEqualTo("[Path] yesterday: 12,431 requests, $18.42, 1.2% failed");

        String text = DailyDigestService.render(LocalDate.of(2026, 11, 19),
                ZoneId.of("Asia/Kolkata"), today, yesterday, List.of("openai"));

        assertThat(text)
                .contains("Path daily digest — 2026-11-19 (Asia/Kolkata)")
                .contains("Requests      12,431        (+8% vs the day before)")
                .contains("Spend         $18.42        (+$1.10)")
                .contains("Tokens        4.1M          cache hit 38.0%, saved $2.90")
                .contains("Failures      149           1.2%  (top: upstream_timeout ×120)")
                .contains("Slowest       claude-sonnet-4.5  p95 3.4s")
                .contains("Top spend     gpt-4o $9.10 · claude-sonnet-4.5 $7.80")
                .contains("Parked        openai (circuit open now)");
    }

    private void configureMailbox() {
        sender = Mockito.mock(JavaMailSender.class);
        when(mail.emailEnabled()).thenReturn(true);
        when(mail.sender()).thenReturn(Optional.of(sender));
        when(mail.recipients()).thenReturn("ops@example.com");
        when(mail.fromAddress()).thenReturn("path@example.com");
        when(breaker.snapshot()).thenReturn(Map.of());
        when(providers.findAllByOrderBySlugAsc()).thenReturn(List.of());
    }

    @Test
    void sendsOneEmailForYesterday() {
        configureMailbox();
        when(usage.countByCreatedAtBetween(any(), any())).thenReturn(5L, 4L);
        when(usage.sumCostBetween(any(), any())).thenReturn(new BigDecimal("1.25"));
        when(usage.sumTokensBetween(any(), any())).thenReturn(900L);

        DailyDigestService.Result result = digest.send(LocalDate.of(2026, 11, 19));

        assertThat(result.sent()).isTrue();
        assertThat(result.reason()).isNull();
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getSubject())
                .isEqualTo("[Path] yesterday: 5 requests, $1.25, 0.0% failed");
        assertThat(message.getValue().getTo()).containsExactly("ops@example.com");
        assertThat(message.getValue().getText()).contains("Path daily digest — 2026-11-19");
        assertThat(registry.counter("path.alerts.sent", "channel", "digest",
                "outcome", "delivered").count()).isEqualTo(1.0);
    }

    @Test
    void aDayWithNoTrafficSendsNothingRatherThanAWallOfZeroes() {
        configureMailbox();
        when(usage.countByCreatedAtBetween(any(), any())).thenReturn(0L);

        DailyDigestService.Result result = digest.send(LocalDate.of(2026, 11, 19));

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("no_traffic");
        verify(mail, never()).sender();
    }

    @Test
    void aDisabledDigestTouchesNothing() {
        props.getDigest().setEnabled(false);

        DailyDigestService.Result result = digest.send(LocalDate.of(2026, 11, 19));

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("disabled");
        verifyNoInteractions(usage, mail, breaker, providers);
    }

    @Test
    void noMailboxMeansNoSendButTheTextIsStillAvailable() {
        when(mail.emailEnabled()).thenReturn(false);
        when(usage.countByCreatedAtBetween(any(), any())).thenReturn(3L, 1L);

        DailyDigestService.Result result = digest.send(LocalDate.of(2026, 11, 19));

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("email_not_configured");
        assertThat(result.text()).contains("Path daily digest");
    }

    @Test
    void aBrokenMailboxLogsAndDoesNotThrow() {
        configureMailbox();
        when(usage.countByCreatedAtBetween(any(), any())).thenReturn(3L, 1L);
        Mockito.doThrow(new MailSendException("smtp down"))
                .when(sender).send(any(SimpleMailMessage.class));

        DailyDigestService.Result result = digest.send(LocalDate.of(2026, 11, 19));

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).isEqualTo("send_failed");
        assertThat(registry.counter("path.alerts.sent", "channel", "digest",
                "outcome", "dropped").count()).isEqualTo(1.0);
    }
}
