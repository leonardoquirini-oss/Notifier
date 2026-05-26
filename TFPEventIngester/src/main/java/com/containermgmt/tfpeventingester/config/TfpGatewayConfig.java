package com.containermgmt.tfpeventingester.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
@ConfigurationProperties(prefix = "tfp-gateway")
@Getter
@Setter
public class TfpGatewayConfig {

    private String baseUrl;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 30000;

    @Bean(name = "tfpGatewayRestTemplate")
    public RestTemplate tfpGatewayRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }
}
