package com.containermgmt.gateeventprocessor.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * A geofencing gate loaded from BERLink wrk_gates.
 * notifyGroup holds the resolved ntf_notification_groups.group_code.
 */
@Getter
@AllArgsConstructor
@ToString
public class Gate {
    /** wrk_gates.id_gate */
    private final int idGate;
    /** wrk_gates.label, shown in the notification text. */
    private final String label;
    /** wrk_gates.latitude (decimal degrees). */
    private final double latitude;
    /** wrk_gates.longitude (decimal degrees). */
    private final double longitude;
    /** wrk_gates.radius in metres. Events outside this radius are ignored. */
    private final double radius;
    /** Resolved ntf_notification_groups.group_code (see /api/notifications/send), may be null. */
    private final String notifyGroup;
}
