package com.containermgmt.tfpeventingester.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "berlink.api")
@Getter
@Setter
public class BerlinkApiConfig {

    private String baseUrl;
    private String apiKey;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 10000;

    @Bean
    public RestTemplate berlinkRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);

        RestTemplate restTemplate = new RestTemplate(factory);

        if (apiKey != null && !apiKey.isBlank()) {
            ClientHttpRequestInterceptor apiKeyInterceptor = (request, body, execution) -> {
                request.getHeaders().set("X-API-Key", apiKey);
                return execution.execute(request, body);
            };
            restTemplate.setInterceptors(List.of(apiKeyInterceptor));
        }

        return restTemplate;
    }
}
