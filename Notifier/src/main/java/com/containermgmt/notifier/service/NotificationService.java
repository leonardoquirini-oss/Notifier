package com.containermgmt.notifier.service;

import com.containermgmt.notifier.config.NotificationConfigProperties;
import com.containermgmt.notifier.dto.DirectEmailRequest;
import com.containermgmt.notifier.dto.StreamEvent;
import com.containermgmt.notifier.model.EmailTemplate;

import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
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

    @Autowired
    private ObjectMapper objectMapper;

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

            // Gestione email diretta (senza template)
            if (mapping.isDirectEmail()) {
                processDirectEmail(event, mapping);
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
                    mapping,
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

    /**
     * Processa un evento di email diretta (senza template).
     * I parametri dell'email vengono estratti direttamente dal payload dell'evento.
     *
     * @param event Evento ricevuto dallo stream
     * @param mapping Mapping configurato per questo evento
     */
    @SuppressWarnings("unchecked")
    private void processDirectEmail(StreamEvent event, NotificationConfigProperties.EventMapping mapping) {
        logger.info("Processing direct email event: stream={}, messageId={}",
            event.getStream(), event.getMessageId());

        try {
            // Estrae il contesto dall'evento
            Map<String, Object> variables = templateRenderer.eventToContext(event.getPayload());
            Object parametersObj = variables.get("parameters");

            if (parametersObj == null) {
                logger.error("Direct email event missing 'parameters' field: messageId={}", event.getMessageId());
                return;
            }

            // Gestione parametri: può essere Map o String (JSON)
            Map<String, Object> parameters;
            if (parametersObj instanceof Map) {
                parameters = (Map<String, Object>) parametersObj;
            } else if (parametersObj instanceof String) {
                // Parse JSON string to Map
                String parametersJson = (String) parametersObj;
                logger.debug("Parsing parameters from JSON string: {}", parametersJson);
                try {
                    parameters = objectMapper.readValue(parametersJson, Map.class);
                } catch (Exception e) {
                    logger.debug("Direct parse failed, trying unescape via ObjectMapper: {}", e.getMessage());
                    // La stringa è stata JSON-escaped (es. inserita come valore in altro JSON).
                    // Deserializziamola prima come stringa JSON per gestire correttamente gli escape.
                    try {
                        String unescapedJson = objectMapper.readValue("\"" + parametersJson + "\"", String.class);
                        logger.debug("Unescaped JSON via ObjectMapper: {}", unescapedJson);
                        parameters = objectMapper.readValue(unescapedJson, Map.class);
                    } catch (Exception e2) {
                        logger.debug("ObjectMapper unescape failed, trying manual: {}", e2.getMessage());
                        // Fallback: unescape manuale
                        String unescaped = parametersJson
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\");
                        parameters = objectMapper.readValue(unescaped, Map.class);
                    }
                }
            } else {
                logger.error("Direct email event 'parameters' has unexpected type: {}", parametersObj.getClass().getName());
                return;
            }

            // DEBUG: diagnostica body dai parameters
            logger.info("DirectEmail DEBUG (NotifService): body from parameters = [{}]",
                parameters.get("body") != null ?
                    ((String)parameters.get("body")).substring(0, Math.min(500, ((String)parameters.get("body")).length())) : "null");

            // Parsing della richiesta email
            DirectEmailRequest request = parseDirectEmailRequest(parameters);

            // Invia email tramite EmailService
            Integer logId = emailService.sendDirectEmail(
                request,
                event.getMessageId(),
                "notifier-service"
            );

            logger.info("Direct email sent successfully: logId={}, messageId={}", logId, event.getMessageId());

        } catch (Exception e) {
            logger.error("Error processing direct email event: stream={}, messageId={}, error={}",
                event.getStream(), event.getMessageId(), e.getMessage(), e);
        }
    }

    /**
     * Effettua il parsing dei parametri dell'evento in un DirectEmailRequest
     *
     * @param parameters Mappa dei parametri dall'evento
     * @return DirectEmailRequest popolato
     */
    @SuppressWarnings("unchecked")
    private DirectEmailRequest parseDirectEmailRequest(Map<String, Object> parameters) {
        DirectEmailRequest request = new DirectEmailRequest();

        // From (opzionale)
        request.setFrom((String) parameters.get("from"));

        // Sender name (opzionale)
        request.setSenderName((String) parameters.get("sender_name"));

        // To (obbligatorio)
        Object toObj = parameters.get("to");
        if (toObj instanceof List) {
            request.setTo((List<String>) toObj);
        } else if (toObj instanceof String) {
            request.setTo(List.of((String) toObj));
        }

        // CC (opzionale)
        Object ccObj = parameters.get("cc");
        if (ccObj instanceof List) {
            request.setCc((List<String>) ccObj);
        } else if (ccObj instanceof String) {
            request.setCc(List.of((String) ccObj));
        }

        // CCN/BCC (opzionale)
        Object ccnObj = parameters.get("ccn");
        if (ccnObj instanceof List) {
            request.setCcn((List<String>) ccnObj);
        } else if (ccnObj instanceof String) {
            request.setCcn(List.of((String) ccnObj));
        }

        // Subject e body (obbligatori)
        request.setSubject((String) parameters.get("subject"));
        request.setBody((String) parameters.get("body"));

        // isHtml (opzionale, con auto-detect)
        Object isHtmlObj = parameters.get("is_html");
        if (isHtmlObj instanceof Boolean) {
            request.setHtml((Boolean) isHtmlObj);
        } else {
            // Auto-detect: cerca tag HTML nel body
            String body = request.getBody();
            request.setHtml(body != null && body.contains("<") && body.contains(">"));
        }

        // Attachments (opzionale)
        Object attachmentsObj = parameters.get("attachments");
        logger.debug("Parsing attachments: value={}, type={}", attachmentsObj,
            attachmentsObj != null ? attachmentsObj.getClass().getName() : "null");
        if (attachmentsObj instanceof List) {
            List<?> attachmentsList = (List<?>) attachmentsObj;
            List<Integer> attachmentIds = new ArrayList<>();
            for (Object att : attachmentsList) {
                logger.debug("Attachment element: value={}, type={}", att,
                    att != null ? att.getClass().getName() : "null");
                if (att instanceof Number) {
                    attachmentIds.add(((Number) att).intValue());
                } else if (att instanceof String) {
                    // Gestisce il caso in cui l'ID sia passato come stringa
                    try {
                        attachmentIds.add(Integer.parseInt((String) att));
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid attachment ID string: {}", att);
                    }
                }
            }
            request.setAttachments(attachmentIds);
            logger.debug("Parsed {} attachments: {}", attachmentIds.size(), attachmentIds);
        } else if (attachmentsObj instanceof Number) {
            // Gestisce il caso di singolo attachment (non array)
            request.setAttachments(List.of(((Number) attachmentsObj).intValue()));
            logger.debug("Parsed single attachment: {}", attachmentsObj);
        } else if (attachmentsObj instanceof String) {
            // Gestisce il caso di singolo attachment come stringa
            try {
                request.setAttachments(List.of(Integer.parseInt((String) attachmentsObj)));
                logger.debug("Parsed single attachment from string: {}", attachmentsObj);
            } catch (NumberFormatException e) {
                logger.warn("Invalid single attachment ID string: {}", attachmentsObj);
            }
        }

        // Delete attachments (opzionale)
        Object deleteObj = parameters.get("delete_attachments");
        if (deleteObj instanceof Boolean) {
            request.setDeleteAttachments((Boolean) deleteObj);
        }

        return request;
    }

}
