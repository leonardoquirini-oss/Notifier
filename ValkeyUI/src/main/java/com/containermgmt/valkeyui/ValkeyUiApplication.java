package com.containermgmt.valkeyui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ValkeyUiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ValkeyUiApplication.class, args);
    }

}
