package com.containermgmt.gateeventprocessor;

import com.containermgmt.gateeventprocessor.config.DamageLabelsProperties;
import com.containermgmt.gateeventprocessor.config.GateEventProperties;
import com.containermgmt.gateeventprocessor.config.PositionEventProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        GateEventProperties.class,
        PositionEventProperties.class,
        DamageLabelsProperties.class
})
public class GateEventProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(GateEventProcessorApplication.class, args);
    }

}
