package com.containermgmt.valkeyui.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamIdUtilsTest {

    @Test
    void timestampMs_extractsMsPart() {
        assertEquals(1709123456789L, StreamIdUtils.timestampMs("1709123456789-0"));
        assertEquals(1709123456789L, StreamIdUtils.timestampMs("1709123456789-42"));
        assertEquals(1709123456789L, StreamIdUtils.timestampMs("1709123456789"));
    }

    @Test
    void timestampMs_returnsZeroOnInvalid() {
        assertEquals(0L, StreamIdUtils.timestampMs(null));
        assertEquals(0L, StreamIdUtils.timestampMs("not-a-number-0"));
    }

    @Test
    void boundaries_useEpochMs() {
        Instant inst = Instant.parse("2026-04-01T00:00:00Z");
        long expected = inst.toEpochMilli();
        assertEquals(expected + "-0", StreamIdUtils.lowerBoundFromInstant(inst));
        assertEquals(expected + "-" + Long.MAX_VALUE, StreamIdUtils.upperBoundFromInstant(inst));
    }

    @Test
    void resolveBoundary_passesThroughStreamIds() {
        assertEquals("1709123456789-0", StreamIdUtils.resolveBoundary("1709123456789-0", false));
        assertEquals("-", StreamIdUtils.resolveBoundary("-", false));
        assertEquals("+", StreamIdUtils.resolveBoundary("+", true));
    }

    @Test
    void resolveBoundary_convertsIso8601() {
        String resolved = StreamIdUtils.resolveBoundary("2026-04-01T00:00:00Z", false);
        Instant inst = Instant.parse("2026-04-01T00:00:00Z");
        assertEquals(inst.toEpochMilli() + "-0", resolved);
    }

}
