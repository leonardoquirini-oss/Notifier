package com.containermgmt.tfpeventingester;

import com.containermgmt.tfpeventingester.config.DamageLabelsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DamageLabelsProperties.class)
public class TfpEventIngesterApplication {

    public static void main(String[] args) {
        SpringApplication.run(TfpEventIngesterApplication.class, args);
    }

}
