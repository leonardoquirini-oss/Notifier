package com.containermgmt.valkeyui.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ConsumerGroupInfo {
    String name;
    long pending;
    String lastDeliveredId;
    long lag;
    List<ConsumerInfo> consumers;
}
