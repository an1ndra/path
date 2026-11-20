package com.anindra.path.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and replaces common personal identifiers with stable tokens.
 *
 * <p>Pure and side-effect free: the same input always produces the same output, which is
 * what makes it safe to run on the request path. Tokens are numbered per call
 * ({@code [EMAIL_1]}), so a response that echoes a token stays readable and two distinct
 * values never collapse into one.
 *
 * <p>Order matters. Card-like digit runs are matched before phone numbers, because a
 * 16-digit card also looks like a very long phone number.
 */
public final class PiiRedactor {

    private PiiRedactor() {
    }

    /** @param counts how many of each kind was replaced, keyed by the token name. */
    public record Result(String text, Map<String, Integer> counts) {
        public boolean redacted() {
            return !counts.isEmpty();
        }

        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /** 13-19 digits, optionally grouped by spaces or dashes. Validated with Luhn below. */
    private static final Pattern CARD = Pattern.compile(
            "(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");

    /**
     * Aadhaar: twelve digits, commonly written in three groups of four. The surrounding
     * lookarounds reject a match that sits inside a longer digit run — a 16-digit id must
     * not be read as "the first twelve digits of an Aadhaar".
     */
    private static final Pattern AADHAAR = Pattern.compile(
            "(?<!\\d)(?<!\\d[ -])\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?!\\d)(?![ -]\\d)");

    /** PAN: five letters, four digits, one letter. */
    private static final Pattern PAN = Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b");

    /** Phone: a run of digits with optional separators, validated in looksLikePhone. */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w+.])\\+?\\d[\\d ().-]{6,}\\d(?![\\w])");

    public static Result redact(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text, Map.of());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        String out = text;
        out = replace(out, "EMAIL", EMAIL, m -> true, counts);
        out = replace(out, "CARD", CARD, PiiRedactor::luhnValid, counts);
        out = replace(out, "AADHAAR", AADHAAR, m -> true, counts);
        out = replace(out, "PAN", PAN, m -> true, counts);
        out = replace(out, "PHONE", PHONE, PiiRedactor::looksLikePhone, counts);
        return new Result(out, counts);
    }

    private static String replace(String text, String token, Pattern pattern,
                                  Function<Matcher, Boolean> accept, Map<String, Integer> counts) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        int n = 0;
        while (matcher.find()) {
            if (!accept.apply(matcher)) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            n++;
            counts.merge(token, 1, Integer::sum);
            matcher.appendReplacement(out, Matcher.quoteReplacement("[" + token + "_" + n + "]"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** A 13-19 digit run is only a card if it passes the checksum. */
    static boolean luhnValid(Matcher matcher) {
        String digits = matcher.group().replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    /**
     * Rejects things that look numeric but are not phone numbers: IDs, timestamps, order
     * numbers and long decimal fractions. A phone number has 8-15 digits and is not part
     * of a longer run.
     */
    static boolean looksLikePhone(Matcher matcher) {
        String whole = matcher.group().trim();
        String digits = whole.replaceAll("[^0-9]", "");
        if (digits.length() < 8 || digits.length() > 15) {
            return false;
        }
        // A date is the common false positive: 2026-11-17 has eight digits and separators.
        if (whole.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            return false;
        }
        // So is a version or a decimal fraction: 1.2.3.4567 must stay readable.
        if (whole.contains(".") && !whole.contains("@")) {
            return false;
        }
        // A leading zero with too few digits is an id, not a phone number.
        if (whole.startsWith("0") && digits.length() < 10) {
            return false;
        }
        if (!whole.startsWith("+")) {
            boolean hasSeparator = whole.matches(".*[ ().-].*");
            if (!hasSeparator) {
                // Contiguous digits are only a phone when they look like a mobile number.
                // This is what keeps a 10-digit epoch timestamp ("1700000000") readable.
                if (digits.length() < 10 || digits.length() > 13) {
                    return false;
                }
                return digits.charAt(0) >= '6' && digits.charAt(0) <= '9';
            }
        }
        return true;
    }
}
