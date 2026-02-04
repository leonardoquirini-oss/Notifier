package com.containermgmt.tfpeventingester.stream;

import java.util.Map;

/**
 * Strategy interface for stream processors.
 * Implement this interface and annotate with @Component to auto-register
 * a new stream consumer via StreamListenerOrchestrator.
 */
public interface StreamProcessor {

    /** Valkey stream key to consume from (e.g. "tfp-unit-events-stream") */
    String streamKey();

    /** Consumer group name (e.g. "tfp-event-ingester-group") */
    String consumerGroup();

    /** Process a single stream message. Called with ActiveJDBC connection active. */
    void process(Map<String, String> fields);

}
