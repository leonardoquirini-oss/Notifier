package com.containermgmt.chatprocessor.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.IdGenerator;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.Table;

/**
 * ActiveJDBC Model per la tabella ai_chatbot_log_calls
 */
@Table("ai_chatbot_log_calls")
@IdName("id_ai_chatbot_log_req")
@IdGenerator("nextval('s_ai_chatbot_log_calls')")
public class AiChatbotLogCall extends Model {

    /**
     * Trova un record tramite correlation_id
     */
    public static AiChatbotLogCall findByCorrelationId(String correlationId) {
        return findFirst("correlation_id = ?", correlationId);
    }

    /**
     * Verifica se esiste un record con il correlation_id specificato
     */
    public static boolean existsByCorrelationId(String correlationId) {
        return count("correlation_id = ?", correlationId) > 0;
    }

}
