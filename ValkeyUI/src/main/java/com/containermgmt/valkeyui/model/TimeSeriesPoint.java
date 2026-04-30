package com.containermgmt.valkeyui.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TimeSeriesPoint {
    long timestampMs;
    Long value;
}
