package com.containermgmt.notifier.service;

import com.containermgmt.notifier.config.NotificationConfigProperties;
import com.containermgmt.notifier.dto.StreamEvent;
import com.containermgmt.notifier.model.EmailTemplate;

import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service per la gestione delle notifiche email basate su eventi stream
 * Orchestrazione tra ricezione eventi, rendering template e invio email
 */
@Service
@Slf4j
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    @Autowired
    private NotificationConfigProperties config;

    @Autowired
    private EmailService emailService;

    @Autowired
    private TemplateRenderer templateRenderer;

    /**
     * Processa un evento dallo stream e invia le notifiche email associate
     *
     * @param event Evento ricevuto dallo stream
     */
    public void processStreamEvent(StreamEvent event) {
        logger.info("Processing stream event: stream={}, messageId={}, eventType={}",
            event.getStream(), event.getMessageId(), event.getEventType());

        try {
            // Trova il mapping configurato per questo evento
            NotificationConfigProperties.EventMapping mapping = findMapping(
                event.getStream(),
                event.getEventType()
            );

            if (mapping == null) {
                logger.debug("No mapping found for stream={}, eventType={}. Skipping.",
                    event.getStream(), event.getEventType());
                return;
            }

            // Carica template dal database
            EmailTemplate template = EmailTemplate.findByCode(mapping.getTemplateCode());

            if (template == null || !template.isUsable()) {
                logger.error("Template not found or not usable: code={}",
                    mapping.getTemplateCode());
                return;
            }

            // Converte i dati dell'evento in variabili per il template
            // Usa eventToContext() che preserva la struttura di array e oggetti nested
            Map<String, Object> variables = templateRenderer.eventToContext(event.getPayload());

            logger.debug("Extracted {} variables from event", variables.size());
            log.info("###DEBUG###  variables : {}",variables);

            Integer logId = emailService.sendFromTemplate(
                    (Integer) template.getId(),
                    mapping.isSingleMail(),
                    mapping.isEmailListSpecified(),
                    variables,
                    event.getStream(),          // entityType
                    null,              // entityId (potrebbe essere estratto dall'evento)
                    "notifier-service"   // sentBy
                );

            logger.info("Email sent successfully: template={}, logId={}, messageId={}",
                mapping.getTemplateCode(), logId, event.getMessageId());

        } catch (Exception e) {
            logger.error("Error processing stream event: stream={}, messageId={}, error={}",
                event.getStream(), event.getMessageId(), e.getMessage(), e);

            // Non rilanciare l'eccezione per permettere l'ACK del messaggio
            // Il log in email_send_log conterrà già l'errore
        }
    }

    /**
     * Trova il mapping configurato per uno stream e tipo di evento
     *
     * @param streamName Nome dello stream
     * @param eventType Tipo di evento
     * @return Mapping trovato o null
     */
    private NotificationConfigProperties.EventMapping findMapping(String streamName, String eventType) {
        if (config.getEventMappings() == null) {
            return null;
        }

        return config.getEventMappings().stream()
            .filter(m -> m.getStream().equals(streamName))
            .filter(m -> m.getEventType().equals(eventType))
            .findFirst()
            .orElse(null);
    }

    /**
     * Verifica se esiste un mapping per uno stream e tipo di evento
     *
     * @param streamName Nome dello stream
     * @param eventType Tipo di evento
     * @return true se esiste un mapping
     */
    public boolean hasMapping(String streamName, String eventType) {
        return findMapping(streamName, eventType) != null;
    }

    /**
     * Ottiene tutti i mapping configurati per uno stream specifico
     *
     * @param streamName Nome dello stream
     * @return Lista di mapping
     */
    public java.util.List<NotificationConfigProperties.EventMapping> getMappingsForStream(String streamName) {
        if (config.getEventMappings() == null) {
            return java.util.Collections.emptyList();
        }

        return config.getEventMappings().stream()
            .filter(m -> m.getStream().equals(streamName))
            .toList();
    }

}
