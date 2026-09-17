package it.gruppobernardini.switchmail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SwitchMailApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwitchMailApplication.class, args);
    }

    /**
     * Ogni lettura del tempo passa da qui. Nei test diventa un Clock.fixed avanzabile a mano,
     * altrimenti i test di backoff e di claim stantio richiederebbero Thread.sleep.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
