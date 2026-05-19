package com.containermgmt.gateeventprocessor;

import com.containermgmt.gateeventprocessor.config.GateEventProperties;
import com.containermgmt.gateeventprocessor.config.GatesProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        GateEventProperties.class,
        GatesProperties.class
})
public class GateEventProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(GateEventProcessorApplication.class, args);
    }

}
