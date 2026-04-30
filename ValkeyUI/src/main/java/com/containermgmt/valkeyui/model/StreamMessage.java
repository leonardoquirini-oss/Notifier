package com.containermgmt.valkeyui.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class StreamMessage {
    String id;
    long timestampMs;
    Map<String, String> fields;
}
