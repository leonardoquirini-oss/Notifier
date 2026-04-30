package com.containermgmt.valkeyui.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;

public final class StreamIdUtils {

    private StreamIdUtils() {
    }

    public static long timestampMs(String streamId) {
        if (streamId == null) {
            return 0L;
        }
        int dash = streamId.indexOf('-');
        String msPart = dash >= 0 ? streamId.substring(0, dash) : streamId;
        try {
            return Long.parseLong(msPart);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static String lowerBoundFromInstant(Instant instant) {
        return instant.toEpochMilli() + "-0";
    }

    public static String upperBoundFromInstant(Instant instant) {
        return instant.toEpochMilli() + "-" + Long.MAX_VALUE;
    }

    public static String resolveBoundary(String input, boolean upper) {
        if (input == null || input.isBlank() || "-".equals(input) || "+".equals(input)) {
            return input;
        }
        if (looksLikeStreamId(input)) {
            return input;
        }
        try {
            Instant instant = Instant.parse(input);
            return upper ? upperBoundFromInstant(instant) : lowerBoundFromInstant(instant);
        } catch (DateTimeParseException e) {
            return input;
        }
    }

    private static boolean looksLikeStreamId(String input) {
        if (input.indexOf('T') >= 0 || input.indexOf(':') >= 0 || input.indexOf('Z') >= 0) {
            return false;
        }
        int dash = input.indexOf('-');
        if (dash < 0) {
            return isAllDigits(input);
        }
        return isAllDigits(input.substring(0, dash))
                && isAllDigits(input.substring(dash + 1));
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

}
