package com.containermgmt.notifier.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.IdGenerator;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.Table;

import java.util.List;

/**
 * Model ActiveJDBC per la tabella email_templates
 * Rappresenta template email con variabili sostituibili
 */
@Table("email_templates")
@IdName("id_template")
@IdGenerator("nextval('email_templates_id_template_seq')")
public class EmailTemplate extends Model {

    /**
     * Validazioni per i campi obbligatori
     */
    static {
        validatePresenceOf(
            "template_code",
            "template_name",
            "subject",
            "body"
        );

        // Template code deve essere alfanumerico con underscore
        validateRegexpOf("template_code", "^[A-Z0-9_]+$")
            .message("deve contenere solo lettere maiuscole, numeri e underscore");

        // Validazione lunghezza template_code e template_name
        // Nota: Validazioni custom complesse vanno gestite manualmente nel controller
    }

    /**
     * Trova template per codice
     */
    public static EmailTemplate findByCode(String templateCode) {
        return EmailTemplate.findFirst(
            "template_code = ? AND is_deleted = FALSE",
            templateCode
        );
    }

    /**
     * Trova template attivi
     */
    public static List<EmailTemplate> findActive() {
        return EmailTemplate.where(
            "is_active = TRUE AND is_deleted = FALSE"
        ).orderBy("template_name");
    }

    /**
     * Trova tutti i template (non eliminati)
     */
    public static List<EmailTemplate> findAllTemplates() {
        return EmailTemplate.where(
            "is_deleted = FALSE"
        ).orderBy("template_name");
    }

    /**
     * Soft delete del template
     */
    public void softDelete(String deletedBy) {
        set("is_deleted", true);
        set("deleted_at", new java.sql.Timestamp(System.currentTimeMillis()));
        set("deleted_by", deletedBy);
        saveIt();
    }

    /**
     * Ripristina template soft-deleted
     */
    public void restore() {
        set("is_deleted", false);
        set("deleted_at", null);
        set("deleted_by", null);
        saveIt();
    }

    /**
     * Verifica se il template è attivo e utilizzabile
     */
    public boolean isUsable() {
        return getBoolean("is_active") && !getBoolean("is_deleted");
    }

    /**
     * Ottiene le liste email associate a questo template
     */
    public List<EmailList> getAssociatedLists() {
        String sql = """
            SELECT l.*
            FROM email_lists l
            INNER JOIN template_email_lists tel ON tel.id_list = l.id_list
            WHERE tel.id_template = ?
              AND tel.is_active = TRUE
              AND l.is_deleted = FALSE
            ORDER BY l.list_name
            """;
        return EmailList.findBySQL(sql, getId());
    }

    /**
     * Associa una lista email a questo template
     */
    public void associateList(Integer listId, String createdBy) {
        // Verifica se associazione già esiste
        TemplateEmailList existing = TemplateEmailList.findFirst(
            "id_template = ? AND id_list = ?",
            getId(), listId
        );

        if (existing != null) {
            // Riattiva se esistente ma inattiva
            if (!existing.getBoolean("is_active")) {
                existing.set("is_active", true);
                existing.saveIt();
            }
        } else {
            // Crea nuova associazione
            TemplateEmailList association = new TemplateEmailList();
            association.set("id_template", getId());
            association.set("id_list", listId);
            association.set("is_active", true);
            association.set("created_by", createdBy);
            association.saveIt();
        }
    }

    /**
     * Rimuove associazione con una lista
     */
    public void dissociateList(Integer listId) {
        TemplateEmailList association = TemplateEmailList.findFirst(
            "id_template = ? AND id_list = ?",
            getId(), listId
        );

        if (association != null) {
            association.set("is_active", false);
            association.saveIt();
        }
    }

    /**
     * Conteggio email inviate con questo template
     */
    public long getSentEmailCount() {

        return EmailSendLog.count("id_template = ? AND send_status = 'SENT'", getId()  );

/*/        EmailSendLog countResult = EmailSendLog.findFirst(
            "SELECT COUNT(*) as total FROM email_send_log WHERE id_template = ? AND send_status = 'SENT'",
            getId()
        );

        if (countResult != null && countResult.get("total") != null) {
            return ((Number) countResult.get("total")).longValue();
        }
        return 0L; */
    }

    /**
     * Verifica se il template contiene una variabile specifica
     */
    public boolean hasVariable(String variableName) {
        String subject = getString("subject");
        String body = getString("body");
        String placeholder = "{{" + variableName + "}}";

        return (subject != null && subject.contains(placeholder)) ||
               (body != null && body.contains(placeholder));
    }
}
