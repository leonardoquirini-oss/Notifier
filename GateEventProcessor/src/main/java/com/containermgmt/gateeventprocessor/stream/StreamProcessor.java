package com.containermgmt.gateeventprocessor.stream;

import java.util.Map;

/**
 * Strategy interface for stream processors.
 * Implement and annotate with @Component to auto-register a new stream consumer
 * via StreamListenerOrchestrator.
 */
public interface StreamProcessor {

    /** Valkey stream key to consume from. */
    String streamKey();

    /** Consumer group name. */
    String consumerGroup();

    /** Process a single stream message. Called with ActiveJDBC connection active. */
    void process(Map<String, String> fields);

}
