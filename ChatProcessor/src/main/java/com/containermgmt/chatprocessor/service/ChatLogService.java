package com.containermgmt.chatprocessor.service;

import com.containermgmt.chatprocessor.dto.ChatEvent;
import com.containermgmt.chatprocessor.model.AiChatbotLogCall;
import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Base;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Service per la gestione del logging delle chiamate chatbot.
 * Gestisce INSERT per request e UPDATE per response.
 */
@Service
@Slf4j
public class ChatLogService {

    /**
     * Processa un evento di tipo chatbot:request
     * Esegue INSERT nella tabella ai_chatbot_log_calls
     */
    public void processRequest(ChatEvent event) {
        String correlationId = event.getCorrelationId();
        Integer userId = event.getUserId();
        String requestUrl = event.getField("requestUrl");
        String request = event.getField("request");
        String requestTimestampStr = event.getField("requestTimestamp");

        if (correlationId == null || correlationId.isEmpty()) {
            log.error("Cannot process request: correlationId is null or empty");
            return;
        }

        if (userId == null) {
            log.error("Cannot process request: userId is null, correlationId={}", correlationId);
            return;
        }

        try {
            // Converti timestamp da epoch millis a Timestamp
            Timestamp requestTimestamp = parseTimestamp(requestTimestampStr);
            if (requestTimestamp == null) {
                requestTimestamp = Timestamp.from(Instant.now());
            }

            // Crea nuovo record
            AiChatbotLogCall logCall = new AiChatbotLogCall();
            logCall.set("correlation_id", correlationId);
            logCall.set("request_timestamp", requestTimestamp);
            logCall.set("id_user_request", userId);
            logCall.set("request_url", requestUrl != null ? requestUrl : "/api/chat");
            logCall.set("request", request != null ? request : "");
            logCall.saveIt();

            log.info("Inserted chatbot request log: id={}, correlationId={}, userId={}", correlationId, userId);

        } catch (Exception e) {
            log.error("Error inserting chatbot request log: correlationId={}, error={}", correlationId, e.getMessage(), e);
        }
    }

    /**
     * Processa un evento di tipo chatbot:response
     * Esegue UPDATE della riga con correlationId corrispondente.
     * Se non esiste, esegue INSERT (edge case).
     */
    public void processResponse(ChatEvent event) {
        String correlationId = event.getCorrelationId();
        String answer = event.getField("answer");
        String answerTimestampStr = event.getField("answerTimestamp");
        Integer userId = event.getUserId();

        if (correlationId == null || correlationId.isEmpty()) {
            log.error("Cannot process response: correlationId is null or empty");
            return;
        }

        try {
            // Converti timestamp da epoch millis a Timestamp
            Timestamp answerTimestamp = parseTimestamp(answerTimestampStr);
            if (answerTimestamp == null) {
                answerTimestamp = Timestamp.from(Instant.now());
            }

            // Cerca record esistente
            AiChatbotLogCall existingLog = AiChatbotLogCall.findByCorrelationId(correlationId);

            if (existingLog != null) {
                // UPDATE del record esistente
                existingLog.set("answer_timestamp", answerTimestamp);
                existingLog.set("answer", answer != null ? answer : "");
                existingLog.saveIt();

                log.info("Updated chatbot response log: id={}, correlationId={}",
                    existingLog.getId(), correlationId);
            } else {
                // Edge case: RESPONSE senza REQUEST
                log.warn("Response without request: correlationId={}, inserting new record", correlationId);

                // Ottieni prossimo ID dalla sequence
                Object nextIdObj = Base.firstCell("SELECT nextval('s_ai_chatbot_log_calls')");
                Long nextId = ((Number) nextIdObj).longValue();

                // Inserisci nuovo record con dati parziali
                AiChatbotLogCall logCall = new AiChatbotLogCall();
                logCall.set("id_ai_chatbot_log_req", nextId);
                logCall.set("correlation_id", correlationId);
                logCall.set("request_timestamp", answerTimestamp); // Usa answerTimestamp come fallback
                logCall.set("id_user_request", userId);
                logCall.set("request_url", "/api/chat");
                logCall.set("request", "[REQUEST MANCANTE]");
                logCall.set("answer_timestamp", answerTimestamp);
                logCall.set("answer", answer != null ? answer : "");
                logCall.saveIt();

                log.info("Inserted orphan chatbot response log: id={}, correlationId={}",
                    nextId, correlationId);
            }

        } catch (Exception e) {
            log.error("Error processing chatbot response log: correlationId={}, error={}",
                correlationId, e.getMessage(), e);
        }
    }

    /**
     * Converte un timestamp in millisecondi (string) in Timestamp
     */
    private Timestamp parseTimestamp(String timestampStr) {
        if (timestampStr == null || timestampStr.isEmpty()) {
            return null;
        }
        try {
            long epochMillis = Long.parseLong(timestampStr);
            return Timestamp.from(Instant.ofEpochMilli(epochMillis));
        } catch (NumberFormatException e) {
            log.warn("Could not parse timestamp: {}", timestampStr);
            return null;
        }
    }

}
