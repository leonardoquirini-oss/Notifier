package it.gruppobernardini.switchmail.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

/** Clock avanzabile a mano al posto di quello di sistema: i test sul tempo non dormono. */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public Clock testClock() {
        return new MutableClock(Instant.parse("2026-09-17T08:00:00Z"));
    }

    @Bean
    public RecordingProcessor recordingProcessor() {
        return new RecordingProcessor("it-proc");
    }
}
