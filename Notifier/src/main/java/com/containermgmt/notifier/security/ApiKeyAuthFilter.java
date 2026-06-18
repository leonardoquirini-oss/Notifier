package com.containermgmt.notifier.security;

import com.containermgmt.notifier.config.EmailApiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Filtro di autenticazione via API key per gli endpoint protetti (POST /email).
 *
 * Controlla l'header X-API-Key contro la lista email.api.keys.
 * In caso di chiave mancante/non valida scrive direttamente una risposta JSON
 * (401), così il body d'errore resta coerente con quello del controller.
 *
 * Agisce SOLO sul path /email; tutte le altre richieste passano oltre
 * (gli endpoint health gestiscono la propria auth inline).
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    public static final String HEADER = "X-API-Key";
    private static final String PROTECTED_PATH = "/email";

    private final EmailApiProperties apiProperties;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(EmailApiProperties apiProperties, ObjectMapper objectMapper) {
        this.apiProperties = apiProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Applica il filtro solo all'endpoint protetto
        return !PROTECTED_PATH.equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        List<String> keys = apiProperties.getKeys();

        if (keys == null || keys.isEmpty()) {
            logger.error("email.api.keys non configurato: endpoint POST /email non utilizzabile");
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "API key non configurate sul server");
            return;
        }

        String apiKey = request.getHeader(HEADER);

        if (apiKey == null || !keys.contains(apiKey)) {
            logger.warn("POST /email: API key mancante o non valida");
            writeError(response, HttpStatus.UNAUTHORIZED, "API key mancante o non valida");
            return;
        }

        // Autenticazione riuscita: popola il SecurityContext
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "email-api-client", null, AuthorityUtils.createAuthorityList("ROLE_EMAIL_API"));
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("error", message);
        body.put("timestamp", Instant.now().toString());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
