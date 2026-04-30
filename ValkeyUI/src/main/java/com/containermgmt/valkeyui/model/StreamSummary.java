package com.containermgmt.valkeyui.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class StreamSummary {
    String key;
    long length;
    String firstId;
    String lastId;
    Long firstTimestampMs;
    Long lastTimestampMs;
    int groupCount;
    long pendingTotal;
    Long memoryBytes;
}
