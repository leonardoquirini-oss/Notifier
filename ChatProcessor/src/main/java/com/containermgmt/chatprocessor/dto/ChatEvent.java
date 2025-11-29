package com.containermgmt.chatprocessor.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Rappresenta un evento chatbot ricevuto da un Valkey stream
 */
@Data
@Slf4j
public class ChatEvent {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static final String EVENT_TYPE_FIELD = "eventType";
    public static final String EVENT_TYPE_REQUEST = "chatbot:request";
    public static final String EVENT_TYPE_RESPONSE = "chatbot:response";

    /**
     * Nome dello stream
     */
    private String stream;

    /**
     * ID del messaggio nello stream
     */
    private String messageId;

    /**
     * Tipo di evento (chatbot:request o chatbot:response)
     */
    private String eventType;

    /**
     * Payload completo del messaggio come mappa chiave-valore
     */
    private Map<String, String> payload;

    /**
     * Timestamp di ricezione
     */
    private long receivedAt;

    public ChatEvent(String stream, String messageId, Map<String, String> payload) {
        this.stream = stream;
        this.messageId = messageId;
        this.payload = payload;
        this.receivedAt = System.currentTimeMillis();
    }

    /**
     * Estrae il valore di un campo dal payload.
     * Se non trovato direttamente, cerca nel JSON "parameters".
     */
    public String getField(String fieldName) {
        String value = null;
        if (payload != null) {
            value = payload.get(fieldName);
            if (value == null) {
                // Cerca nel JSON parameters
                String parameters = payload.get("parameters");
                if (parameters != null && !parameters.isEmpty()) {
                    try {
                        // Unescape del JSON se necessario (es: {\"key\":\"value\"} -> {"key":"value"})
                        String jsonStr = unescapeJson(parameters);
                        Map<String, Object> paramsMap = objectMapper.readValue(
                            jsonStr,
                            new TypeReference<Map<String, Object>>() {}
                        );
                        Object paramValue = paramsMap.get(fieldName);
                        if (paramValue != null) {
                            value = paramValue.toString();
                        }
                    } catch (Exception e) {
                        log.warn("Could not parse parameters JSON: {}", e.getMessage());
                    }
                }
            }
        }
        return value;
    }

    /**
     * Unescape di una stringa JSON escaped
     */
    private String unescapeJson(String json) {
        if (json == null) {
            return null;
        }
        // Rimuovi virgolette esterne se presenti (es: "{ ... }" -> { ... })
        String result = json;
        if (result.startsWith("\"") && result.endsWith("\"") && result.length() > 1) {
            result = result.substring(1, result.length() - 1);
        }
        // Unescape caratteri
        result = result.replace("\\\"", "\"");
        result = result.replace("\\\\", "\\");
        return result;
    }

    /**
     * Verifica se il payload contiene un campo
     */
    public boolean hasField(String fieldName) {
        return payload != null && payload.containsKey(fieldName);
    }

    /**
     * Ottiene il correlationId dall'evento
     */
    public String getCorrelationId() {
        return getField("correlationId");
    }

    /**
     * Ottiene lo userId (id_employee) dall'evento
     */
    public Integer getUserId() {
        String userId = getField("userId");
        if (userId != null) {
            try {
                return Integer.parseInt(userId);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Verifica se questo è un evento di tipo REQUEST
     */
    public boolean isRequest() {
        return EVENT_TYPE_REQUEST.equals(eventType);
    }

    /**
     * Verifica se questo è un evento di tipo RESPONSE
     */
    public boolean isResponse() {
        return EVENT_TYPE_RESPONSE.equals(eventType);
    }

}
