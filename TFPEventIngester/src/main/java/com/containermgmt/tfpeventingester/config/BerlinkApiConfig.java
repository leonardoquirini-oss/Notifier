package com.containermgmt.tfpeventingester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "berlink.api")
public class BerlinkApiConfig {

    private String baseUrl;
    private String apiKey;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    @Bean
    public RestTemplate berlinkRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);

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
