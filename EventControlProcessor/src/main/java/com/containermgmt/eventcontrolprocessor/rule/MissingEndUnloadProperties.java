package com.containermgmt.eventcontrolprocessor.rule;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the "BEGIN_UNLOAD without END_UNLOAD" rule.
 * Prefix: control.rules.missing-end-unload
 */
@ConfigurationProperties(prefix = "control.rules.missing-end-unload")
@Getter
@Setter
public class MissingEndUnloadProperties implements PairRuleSettings {

    private boolean enabled = true;

    /** Hours to wait after a BEGIN_UNLOAD before considering its END_UNLOAD "missing". */
    private int graceHours = 6;

    /** Only scan BEGIN_UNLOAD events newer than this many hours (bounds the query). */
    private int lookbackHours = 48;

    /** GPS tolerance (meters) when matching the END_UNLOAD position to the BEGIN_UNLOAD position. */
    private double toleranceMeters = 200;

    /** Notification channels: any of berlink, whatsapp, email. */
    private List<String> channels = new ArrayList<>();

    /** Notification group code (BERLink in-app + WhatsApp recipients). */
    private String groupCode;

    /** BERLink notification_type value. */
    private String notificationType = "missing_end_unload";

    /** Email recipients (used only when the email channel is enabled). */
    private List<String> emailRecipients = new ArrayList<>();
}
