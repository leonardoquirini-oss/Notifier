package com.containermgmt.valkeyui.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TimeBucket {
    long timestampMs;
    long count;
}
