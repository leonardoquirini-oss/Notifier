package com.containermgmt.eventcontrolprocessor.rule;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the "BEGIN_LOAD without END_LOAD" rule.
 * Prefix: control.rules.missing-end-load
 */
@ConfigurationProperties(prefix = "control.rules.missing-end-load")
@Getter
@Setter
public class MissingEndLoadProperties implements PairRuleSettings {

    private boolean enabled = true;

    /** Hours to wait after a BEGIN_LOAD before considering its END_LOAD "missing". */
    private int graceHours = 6;

    /** Only scan BEGIN_LOAD events newer than this many hours (bounds the query). */
    private int lookbackHours = 48;

    /** GPS tolerance (meters) when matching the END_LOAD position to the BEGIN_LOAD position. */
    private double toleranceMeters = 200;

    /** Notification channels: any of berlink, whatsapp, email. */
    private List<String> channels = new ArrayList<>();

    /** Notification group code (BERLink in-app + WhatsApp recipients). */
    private String groupCode;

    /** BERLink notification_type value. */
    private String notificationType = "missing_end_load";

    /** Email recipients (used only when the email channel is enabled). */
    private List<String> emailRecipients = new ArrayList<>();
}
