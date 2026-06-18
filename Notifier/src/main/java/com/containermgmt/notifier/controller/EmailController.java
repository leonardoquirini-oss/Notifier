package com.containermgmt.notifier.controller;

import com.containermgmt.notifier.dto.DirectEmailRequest;
import com.containermgmt.notifier.service.EmailService;
import org.javalite.activejdbc.Base;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoint REST per invio email diretta da app esterne.
 *
 * POST /email : invia un'email passando destinatari, oggetto e corpo nel body JSON.
 * Autenticazione gestita da ApiKeyAuthFilter (header X-API-Key) PRIMA del controller.
 *
 * Il body JSON usa gli stessi campi snake_case del payload stream email:send
 * (to, cc, ccn, subject, body, from, sender_name, is_html, attachments, delete_attachments).
 */
@RestController
public class EmailController {

    private static final Logger logger = LoggerFactory.getLogger(EmailController.class);

    private final EmailService emailService;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${spring.datasource.driver-class-name}")
    private String driverClassName;

    public EmailController(EmailService emailService) {
        this.emailService = emailService;
    }

    @PostMapping("/email")
    public ResponseEntity<Map<String, Object>> sendEmail(
            @RequestBody DirectEmailRequest request) {

        // Validazione input minima (EmailService valida in dettaglio)
        if (request == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Body richiesta mancante");
        }
        if (request.getTo() == null || request.getTo().isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Campo 'to' obbligatorio");
        }
        if (request.getSubject() == null || request.getSubject().trim().isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Campo 'subject' obbligatorio");
        }

        // Auto-detect HTML se non esplicitato dal client (coerente col path stream)
        if (!request.isHtml()) {
            String body = request.getBody();
            request.setHtml(body != null && body.contains("<") && body.contains(">"));
        }

        // ActiveJDBC: la connessione è per-thread; il thread HTTP non ne ha una.
        boolean connectionOpened = false;
        try {
            if (!Base.hasConnection()) {
                Base.open(driverClassName, dbUrl, dbUsername, dbPassword);
                connectionOpened = true;
            }

            Integer logId = emailService.sendDirectEmail(request, "rest-api", "email-api");

            logger.info("POST /email: email inviata, logId={}, to={}", logId, request.getTo());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "sent");
            body.put("logId", logId);
            body.put("timestamp", Instant.now().toString());
            return ResponseEntity.ok(body);

        } catch (IllegalArgumentException e) {
            logger.warn("POST /email: richiesta non valida: {}", e.getMessage());
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            logger.error("POST /email: errore invio email: {}", e.getMessage(), e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Errore durante l'invio dell'email");
        } finally {
            if (connectionOpened && Base.hasConnection()) {
                Base.close();
            }
        }
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("error", message);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
