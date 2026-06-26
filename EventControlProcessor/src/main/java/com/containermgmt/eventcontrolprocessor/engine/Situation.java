package com.containermgmt.eventcontrolprocessor.engine;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * A detected anomalous situation produced by a {@link ControlRule}.
 *
 * <p>{@code ruleCode} + {@code dedupKey} together identify the situation uniquely: the engine
 * persists it with a unique constraint on that pair so the notification is dispatched exactly once,
 * even across restarts and overlapping scheduler ticks.</p>
 */
@Getter
@Builder
public class Situation {

    /** Code of the rule that produced this situation (e.g. "MISSING_END_LOAD"). */
    private final String ruleCode;

    /** Stable key that makes the situation unique within its rule (e.g. the source event id). */
    private final String dedupKey;

    /** Unit involved, for logging/auditing (nullable). */
    private final String unitNumber;

    /** Short title for the notification. */
    private final String title;

    /** Human-readable body of the notification. */
    private final String message;

    /** BERLink notification_type (used by the BERLINK channel). */
    private final String notificationType;

    /** Channels this situation must be dispatched to. */
    private final List<Channel> channels;

    /** BERLink/WhatsApp notification group code (recipients resolution). */
    private final String groupCode;

    /** Explicit email recipients (used by the EMAIL channel). */
    private final List<String> emailRecipients;

    /** Optional JSON blob persisted in evt_event_checks.details for auditing. */
    private final String detailsJson;
}
