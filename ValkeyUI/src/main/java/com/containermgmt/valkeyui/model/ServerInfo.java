package com.containermgmt.valkeyui.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class ServerInfo {
    String version;
    String mode;
    long uptimeSeconds;
    long connectedClients;
    long usedMemory;
    long usedMemoryPeak;
    long usedMemoryRss;
    String usedMemoryHuman;
    String usedMemoryPeakHuman;
    long evictedKeys;
    long keyspaceHits;
    long keyspaceMisses;
    Map<Integer, Long> dbSizes;
    Double pingLatencyMs;
    String role;
}
