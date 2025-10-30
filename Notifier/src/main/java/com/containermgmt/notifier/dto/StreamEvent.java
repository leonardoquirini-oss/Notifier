package com.containermgmt.notifier.dto;

import lombok.Data;

import java.util.Map;

/**
 * Rappresenta un evento ricevuto da un Valkey stream
 */
@Data
public class StreamEvent {

    public final static String EVENT_TYPE_FIELD = "eventType";

    /**
     * Nome dello stream
     */
    private String stream;

    /**
     * ID del messaggio nello stream
     */
    private String messageId;

    /**
     * Tipo di evento (estratto dal payload)
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

    public StreamEvent(String stream, String messageId, Map<String, String> payload) {
        this.stream = stream;
        this.messageId = messageId;
        this.payload = payload;
        this.receivedAt = System.currentTimeMillis();
    }

    /**
     * Estrae il valore di un campo dal payload
     */
    public String getField(String fieldName) {
        return payload != null ? payload.get(fieldName) : null;
    }

    /**
     * Verifica se il payload contiene un campo
     */
    public boolean hasField(String fieldName) {
        return payload != null && payload.containsKey(fieldName);
    }

}
