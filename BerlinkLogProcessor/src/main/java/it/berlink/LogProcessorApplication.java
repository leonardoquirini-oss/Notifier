package it.berlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FlowCenter Collector - edge agent for Berlink query logs.
 *
 * Tails a log file containing SQL query execution data, parses each execution,
 * and ships raw execution points to FlowCenter central over HTTP. Metric
 * aggregation lives in central — this process only collects and forwards.
 *
 * Features:
 * - Real-time log file monitoring with polling
 * - Log rotation support (detects file truncation/replacement)
 * - Fault tolerance (read position persisted to a local file)
 * - Batched HTTP ingestion with a bounded on-disk spool when central is down
 */
@SpringBootApplication
public class LogProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogProcessorApplication.class, args);
    }
}
