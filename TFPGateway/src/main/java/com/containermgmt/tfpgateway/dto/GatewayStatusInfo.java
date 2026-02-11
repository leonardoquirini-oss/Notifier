package com.containermgmt.tfpgateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO returned by GET /gateway/status (JSON) and used to populate
 * the status section of the gateway configuration page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayStatusInfo {

    /** Overall state: RUNNING, STOPPED, PARTIAL. */
    private String state;

    /** Broker URL currently in use. */
    private String brokerUrl;

    /** Per-listener details. */
    private List<ListenerInfo> listeners;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListenerInfo {
        private String address;
        private String destination;
        private boolean running;
        private int activeConsumers;
    }
}
