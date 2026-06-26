package com.containermgmt.eventcontrolprocessor.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Notification channels a situation can be dispatched to. */
public enum Channel {
    BERLINK,
    WHATSAPP,
    EMAIL;

    /** Lenient parse from config strings; unknown/blank values are skipped. */
    public static List<Channel> parse(List<String> raw) {
        List<Channel> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            try {
                result.add(Channel.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // skip unknown channel names
            }
        }
        return result;
    }
}
