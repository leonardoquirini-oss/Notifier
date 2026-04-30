package com.containermgmt.valkeyui.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AuditEntry {
    String id;
    long timestampMs;
    String user;
    String operation;
    String stream;
    String params;
    String result;
    Long durationMs;
}
