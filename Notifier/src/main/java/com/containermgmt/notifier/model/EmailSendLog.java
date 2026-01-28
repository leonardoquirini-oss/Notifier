package com.containermgmt.notifier.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.IdGenerator;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.Table;

import java.sql.Timestamp;
import java.util.List;

/**
 * Model ActiveJDBC per la tabella email_send_log
 * Rappresenta il log di audit delle email inviate
 */
@Table("email_send_log")
@IdName("id_log")
@IdGenerator("nextval('email_send_log_id_log_seq')")
public class EmailSendLog extends Model {

    /**
     * Validazioni per i campi obbligatori
     */
    static {
        validatePresenceOf(
            "subject",
            "send_status"
        );

        // Validazione send_status
        // Nota: Validazione custom per send_status gestita manualmente
        // Nota: body non è obbligatorio per email dirette
    }

    /**
     * Stati possibili per send_status
     */
    public static class Status {
        public static final String PENDING = "PENDING";
        public static final String SENT = "SENT";
        public static final String FAILED = "FAILED";
        public static final String RETRY = "RETRY";
    }

    /**
     * Crea un nuovo log di invio email
     */
    public static EmailSendLog createLog(
            Integer templateId,
            String templateCode,
            Integer listId,
            String recipientsTo,
            String recipientsCc,
            String recipientsBcc,
            String subject,
            String body,
            String variablesUsed,
            String entityType,
            Integer entityId,
            String sentBy) {

        EmailSendLog log = new EmailSendLog();
        log.set("id_template", templateId);
        log.set("template_code", templateCode);
        log.set("id_list", listId);
        log.set("recipients_to", recipientsTo);
        log.set("recipients_cc", recipientsCc);
        log.set("recipients_bcc", recipientsBcc);
        log.set("subject", subject);
        log.set("body", body);
        log.set("variables_used", variablesUsed);
        log.set("entity_type", entityType);
        log.set("entity_id", entityId);
        log.set("send_status", Status.PENDING);
        log.set("sent_by", sentBy);
        log.set("retry_count", 0);
        log.saveIt();

        return log;
    }

    /**
     * Marca il log come inviato con successo
     */
    public void markAsSent(String smtpMessageId) {
        set("send_status", Status.SENT);
        set("smtp_message_id", smtpMessageId);
        set("sent_at", new Timestamp(System.currentTimeMillis()));
        saveIt();
    }

    /**
     * Marca il log come fallito
     */
    public void markAsFailed(String errorMessage) {
        set("send_status", Status.FAILED);
        set("error_message", errorMessage);
        saveIt();
    }

    /**
     * Marca per retry e incrementa contatore
     */
    public void markForRetry(String errorMessage) {
        Integer retryCount = getInteger("retry_count");
        if (retryCount == null) retryCount = 0;

        set("send_status", Status.RETRY);
        set("error_message", errorMessage);
        set("retry_count", retryCount + 1);
        set("last_retry_at", new Timestamp(System.currentTimeMillis()));
        saveIt();
    }

    /**
     * Trova log per template
     */
    public static List<EmailSendLog> findByTemplate(Integer templateId) {
        return EmailSendLog.where(
            "id_template = ?",
            templateId
        ).orderBy("sent_at DESC");
    }

    /**
     * Trova log per stato
     */
    public static List<EmailSendLog> findByStatus(String status) {
        return EmailSendLog.where(
            "send_status = ?",
            status
        ).orderBy("sent_at DESC");
    }

    /**
     * Trova log per entità
     */
    public static List<EmailSendLog> findByEntity(String entityType, Integer entityId) {
        return EmailSendLog.where(
            "entity_type = ? AND entity_id = ?",
            entityType, entityId
        ).orderBy("sent_at DESC");
    }

    /**
     * Trova log in pending o retry (per job schedulato)
     */
    public static List<EmailSendLog> findPendingOrRetry() {
        return EmailSendLog.where(
            "send_status IN (?, ?)",
            Status.PENDING, Status.RETRY
        ).orderBy("sent_at ASC");
    }

    /**
     * Trova log da ritentare (max retry non superato)
     */
    public static List<EmailSendLog> findRetryable(int maxRetries) {
        return EmailSendLog.where(
            "send_status = ? AND retry_count < ?",
            Status.RETRY, maxRetries
        ).orderBy("last_retry_at ASC");
    }

    /**
     * Statistiche email per template
     */
    public static List<EmailSendLog> getStatsByTemplate() {
        String sql = """
            SELECT
                template_code,
                send_status,
                COUNT(*) as count,
                MIN(sent_at) as first_sent,
                MAX(sent_at) as last_sent
            FROM email_send_log
            WHERE template_code IS NOT NULL
            GROUP BY template_code, send_status
            ORDER BY template_code, send_status
            """;
        return EmailSendLog.findBySQL(sql);
    }

    /**
     * Statistiche email per periodo
     */
    public static List<EmailSendLog> getStatsByPeriod(String startDate, String endDate) {
        String sql = """
            SELECT
                DATE(sent_at) as send_date,
                send_status,
                COUNT(*) as count
            FROM email_send_log
            WHERE sent_at BETWEEN ? AND ?
            GROUP BY DATE(sent_at), send_status
            ORDER BY send_date DESC, send_status
            """;
        return EmailSendLog.findBySQL(sql, startDate, endDate);
    }

    /**
     * Cleanup log vecchi (opzionale, per job di manutenzione)
     */
    public static int cleanupOldLogs(int daysOld) {
        String sql = String.format(
            "DELETE FROM email_send_log WHERE sent_at < CURRENT_TIMESTAMP - INTERVAL '%d days'",
            daysOld
        );

        // Usando Base.exec() per DELETE
        return org.javalite.activejdbc.Base.exec(sql);
    }

    /**
     * Verifica se è inviato con successo
     */
    public boolean isSent() {
        return Status.SENT.equals(getString("send_status"));
    }

    /**
     * Verifica se è fallito
     */
    public boolean isFailed() {
        return Status.FAILED.equals(getString("send_status"));
    }

    /**
     * Verifica se è in pending
     */
    public boolean isPending() {
        return Status.PENDING.equals(getString("send_status"));
    }

    /**
     * Verifica se è in retry
     */
    public boolean isRetry() {
        return Status.RETRY.equals(getString("send_status"));
    }

    /**
     * Ottiene il numero di tentativi
     */
    public int getRetryCount() {
        Integer count = getInteger("retry_count");
        return count != null ? count : 0;
    }
}
