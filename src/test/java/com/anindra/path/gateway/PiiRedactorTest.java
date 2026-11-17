package com.anindra.path.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    private String redact(String text) {
        return PiiRedactor.redact(text).text();
    }

    @Test
    void replacesEmailsWithStableTokens() {
        var result = PiiRedactor.redact("write to alice@example.com and bob@corp.co.uk");

        assertThat(result.text()).isEqualTo("write to [EMAIL_1] and [EMAIL_2]");
        assertThat(result.counts()).containsEntry("EMAIL", 2);
    }

    @Test
    void replacesCardNumbersThatPassTheChecksum() {
        // 4242 4242 4242 4242 is a valid Luhn number (Stripe's test card).
        var result = PiiRedactor.redact("charge 4242 4242 4242 4242 please");

        assertThat(result.text()).contains("[CARD_1]");
        assertThat(result.text()).doesNotContain("4242");
    }

    @Test
    void leavesDigitRunsThatAreNotCardsAlone() {
        // Passes the 16-digit shape but fails Luhn, so it is an id, not a card.
        assertThat(redact("order 1234 5678 9012 3456 shipped")).contains("1234 5678 9012 3456");
    }

    @Test
    void replacesAadhaarAndPan() {
        assertThat(redact("aadhaar 1234 5678 9012")).contains("[AADHAAR_1]");
        assertThat(redact("pan ABCDE1234F")).contains("[PAN_1]");
    }

    @Test
    void replacesPhoneNumbers() {
        assertThat(redact("call +91 98765 43210 now")).contains("[PHONE_1]");
        assertThat(redact("call 9876543210 now")).contains("[PHONE_1]");
    }

    @Test
    void leavesTimestampsAndShortNumbersAlone() {
        String text = "at 2026-11-17T10:45:24Z request 42 took 250ms";
        assertThat(redact(text)).isEqualTo(text);
    }

    @Test
    void tokensAreNumberedPerCallAndRepeatable() {
        String once = redact("a@b.com a@b.com");
        assertThat(once).isEqualTo("[EMAIL_1] [EMAIL_2]");
        // Deterministic: the same input redacts identically every time.
        assertThat(redact("a@b.com a@b.com")).isEqualTo(once);
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertThat(PiiRedactor.redact("").text()).isEmpty();
        assertThat(PiiRedactor.redact(null).text()).isNull();
        assertThat(PiiRedactor.redact(null).redacted()).isFalse();
    }

    @Test
    void reportsWhatItReplaced() {
        var result = PiiRedactor.redact("mail me at a@b.com or ring 9876543210");
        assertThat(result.redacted()).isTrue();
        assertThat(result.total()).isEqualTo(2);
    }

    @Test
    void leavesEpochTimestampsAlone() {
        // A 10-digit epoch starts with 1, so it is not mistaken for a mobile number.
        assertThat(redact("{\"created\":1700000000}")).isEqualTo("{\"created\":1700000000}");
    }

    @Test
    void aCleanPromptIsUntouched() {
        String text = "Summarise the second paragraph in one sentence.";
        assertThat(PiiRedactor.redact(text).counts()).isEmpty();
        assertThat(redact(text)).isEqualTo(text);
    }
}
