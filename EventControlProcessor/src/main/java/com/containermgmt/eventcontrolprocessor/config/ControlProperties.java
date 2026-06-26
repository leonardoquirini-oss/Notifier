package com.containermgmt.eventcontrolprocessor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global settings for the control engine (the scheduler that runs all rules).
 * Per-rule settings live in their own dedicated properties class
 * (e.g. {@code control.rules.missing-end-load} -> MissingEndLoadProperties).
 */
@ConfigurationProperties(prefix = "control")
@Getter
@Setter
public class ControlProperties {

    /** When false, the scheduled evaluation is skipped entirely. */
    private boolean enabled = true;

    /** Spring cron expression driving how often all rules are evaluated. */
    private String pollCron = "0 */15 * * * *";
}
