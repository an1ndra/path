package com.anindra.path.api;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses windows like 30m, 24h, 7d, 4w or ISO-8601 (PT24H). */
public final class Durations {

    private static final Pattern SHORT = Pattern.compile("^(\\d+)([smhdw])$");

    private Durations() {
    }

    public static Duration parse(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase();
        Matcher m = SHORT.matcher(value);
        try {
            if (m.matches()) {
                long amount = Long.parseLong(m.group(1));
                return switch (m.group(2)) {
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    case "d" -> Duration.ofDays(amount);
                    case "w" -> Duration.ofDays(amount * 7);
                    default -> fallback;
                };
            }
            return Duration.parse(value.toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest(
                    "Invalid window '" + raw + "'. Use forms like 30m, 24h, 7d or PT24H.", "window");
        }
    }

    public static Duration cap(Duration d, Duration max) {
        return d.compareTo(max) > 0 ? max : d;
    }
}
