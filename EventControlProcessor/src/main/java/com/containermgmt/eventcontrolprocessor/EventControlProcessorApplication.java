package com.containermgmt.eventcontrolprocessor;

import com.containermgmt.eventcontrolprocessor.config.ControlProperties;
import com.containermgmt.eventcontrolprocessor.rule.MissingEndLoadProperties;
import com.containermgmt.eventcontrolprocessor.rule.MissingEndUnloadProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        ControlProperties.class,
        MissingEndLoadProperties.class,
        MissingEndUnloadProperties.class
})
public class EventControlProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventControlProcessorApplication.class, args);
    }

}
