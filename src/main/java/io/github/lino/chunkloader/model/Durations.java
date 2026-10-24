package io.github.lino.chunkloader.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Durations {
    private static final Pattern DURATION = Pattern.compile("([0-9]+)([smhd]?)");
    private static final long TICKS_PER_SECOND = 20;

    private Durations() {
    }

    public static long parse(String input) {
        Objects.requireNonNull(input, "Duration is required");
        String value = input.strip().toLowerCase(Locale.ROOT);
        if (value.equals("inf") || value.equals("infinite")) {
            return -1;
        }
        Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Use a positive duration such as 90s, 30m, 2h, 1d, or infinite");
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            if (amount == 0) {
                throw new IllegalArgumentException("Duration must be positive");
            }
            long seconds = switch (matcher.group(2)) {
                case "s" -> 1;
                case "h" -> 3_600;
                case "d" -> 86_400;
                default -> 60;
            };
            return Math.multiplyExact(amount, seconds * TICKS_PER_SECOND);
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Duration is too large", exception);
        }
    }

    public static String format(long ticks) {
        if (ticks < -1) {
            throw new IllegalArgumentException("Duration must be nonnegative or -1 for infinite");
        }
        if (ticks == -1) {
            return "Infinite";
        }
        long seconds = ticks / TICKS_PER_SECOND + (ticks % TICKS_PER_SECOND == 0 ? 0 : 1);
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long remainder = seconds % 60;
        StringBuilder result = new StringBuilder();
        append(result, days, "d");
        append(result, hours, "h");
        append(result, minutes, "m");
        append(result, remainder, "s");
        return result.isEmpty() ? "0s" : result.toString();
    }

    private static void append(StringBuilder result, long amount, String unit) {
        if (amount == 0) {
            return;
        }
        if (!result.isEmpty()) {
            result.append(' ');
        }
        result.append(amount).append(unit);
    }
}
