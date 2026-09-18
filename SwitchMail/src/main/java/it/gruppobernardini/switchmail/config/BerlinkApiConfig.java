package it.gruppobernardini.switchmail.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Accesso a BERLink: base URL, API key, timeout.
 *
 * <p>Stesso pattern dei fratelli (X-API-Key iniettata da un interceptor), con una differenza: qui
 * l'API key non ha un default. In TFPEventIngester una key viva e' committata in git come default di
 * un placeholder, quindi la produzione ci gira sopra per sempre; qui un placeholder non risolto fa
 * fallire il context all'avvio, che e' il comportamento voluto.
 */
@Configuration
@ConfigurationProperties(prefix = "berlink.api")
@Getter
@Setter
public class BerlinkApiConfig {

    private String baseUrl;
    private String apiKey;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 15000;
    /** Gruppo destinatario delle notifiche admin (scope della API key). */
    private String notificationGroupCode = "cd";

    @Bean
    public RestTemplate berlinkRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        RestTemplate rest = new RestTemplate(factory);
        if (apiKey != null && !apiKey.isBlank()) {
            ClientHttpRequestInterceptor apiKeyInterceptor = (request, body, execution) -> {
                request.getHeaders().set("X-API-Key", apiKey);
                return execution.execute(request, body);
            };
            rest.setInterceptors(List.of(apiKeyInterceptor));
        }
        return rest;
    }

    /** Base URL senza slash finale, cosi' i path si concatenano senza doppioni. */
    public String url(String path) {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }
}
