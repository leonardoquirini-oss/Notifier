package com.containermgmt.valkeyui.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "valkeyui")
public class ValkeyUiProperties {

    private boolean readOnly = false;
    private String adminUsername = "admin";
    private String adminToken = "change-me";

    private Sampler sampler = new Sampler();
    private Audit audit = new Audit();
    private Query query = new Query();

    @Data
    public static class Sampler {
        private boolean enabled = true;
        private long intervalMs = 60000;
        private String metricsStreamPrefix = "valkeyui:metrics:stream:";
        private long metricsRetention = 43200;
    }

    @Data
    public static class Audit {
        private String streamKey = "valkeyui:audit";
        private long retention = 10000;
    }

    @Data
    public static class Query {
        private long maxMessagesPerCall = 50000;
    }

}
