package com.containermgmt.valkeyui.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ConsumerInfo {
    String name;
    long pending;
    long idleMs;
}
