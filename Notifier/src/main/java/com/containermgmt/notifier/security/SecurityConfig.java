package com.containermgmt.notifier.security;

import com.containermgmt.notifier.config.EmailApiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configurazione Spring Security.
 *
 * - Stateless (nessuna sessione), CSRF disabilitato (API server-to-server).
 * - L'auth via API key è delegata ad ApiKeyAuthFilter, che protegge POST /email.
 * - Tutti gli altri path sono permitAll: gli endpoint health gestiscono la
 *   propria auth inline (X-API-Key su /api/health/ready).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           EmailApiProperties apiProperties,
                                           ObjectMapper objectMapper) throws Exception {

        ApiKeyAuthFilter apiKeyFilter = new ApiKeyAuthFilter(apiProperties, objectMapper);

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
