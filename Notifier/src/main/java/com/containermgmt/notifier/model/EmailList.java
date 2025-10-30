package com.containermgmt.notifier.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.IdGenerator;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Model ActiveJDBC per la tabella email_lists
 * Rappresenta liste di distribuzione email
 */
@Table("email_lists")
@IdName("id_list")
@IdGenerator("nextval('email_lists_id_list_seq')")
public class EmailList extends Model {

    /**
     * Validazioni per i campi obbligatori
     */
    static {
        validatePresenceOf(
            "list_code",
            "list_name"
        );

        // List code deve essere alfanumerico con underscore
        validateRegexpOf("list_code", "^[A-Z0-9_]+$")
            .message("deve contenere solo lettere maiuscole, numeri e underscore");

        // Validazione lunghezza list_code e list_name
        // Nota: Validazioni custom complesse vanno gestite manualmente nel controller
    }

    /**
     * Trova lista per codice
     */
    public static EmailList findByCode(String listCode) {
        return EmailList.findFirst(
            "list_code = ? AND is_deleted = FALSE",
            listCode
        );
    }

    /**
     * Trova liste attive
     */
    public static List<EmailList> findActive() {
        return EmailList.where(
            "is_active = TRUE AND is_deleted = FALSE"
        ).orderBy("list_name");
    }

    /**
     * Trova tutte le liste (non eliminate)
     */
    public static List<EmailList> findAllLists() {
        return EmailList.where(
            "is_deleted = FALSE"
        ).orderBy("list_name");
    }

    /**
     * Soft delete della lista
     */
    public void softDelete(String deletedBy) {
        set("is_deleted", true);
        set("deleted_at", new java.sql.Timestamp(System.currentTimeMillis()));
        set("deleted_by", deletedBy);
        saveIt();
    }

    /**
     * Ripristina lista soft-deleted
     */
    public void restore() {
        set("is_deleted", false);
        set("deleted_at", null);
        set("deleted_by", null);
        saveIt();
    }

    /**
     * Verifica se la lista è attiva e utilizzabile
     */
    public boolean isUsable() {
        return getBoolean("is_active") && !getBoolean("is_deleted");
    }

    /**
     * Ottiene tutti i destinatari della lista
     */
    public List<EmailListRecipient> getAllRecipients() {
        return EmailListRecipient.where(
            "id_list = ? AND is_active = TRUE",
            getId()
        ).orderBy("email_address");
    }

    /**
     * Ottiene destinatari per tipo (TO, CC, BCC)
     */
    public List<EmailListRecipient> getRecipientsByType(String recipientType) {
        return EmailListRecipient.where(
            "id_list = ? AND recipient_type = ? AND is_active = TRUE",
            getId(), recipientType
        ).orderBy("email_address");
    }

    /**
     * Ottiene array di email addresses per tipo
     */
    public List<String> getEmailAddressesByType(String recipientType) {
        List<EmailListRecipient> recipients = getRecipientsByType(recipientType);
        List<String> addresses = new ArrayList<>();
        for (EmailListRecipient recipient : recipients) {
            addresses.add(recipient.getString("email_address"));
        }
        return addresses;
    }

    /**
     * Ottiene tutti gli indirizzi TO
     */
    public List<String> getToAddresses() {
        return getEmailAddressesByType("TO");
    }

    /**
     * Ottiene tutti gli indirizzi CC
     */
    public List<String> getCcAddresses() {
        return getEmailAddressesByType("CC");
    }

    /**
     * Ottiene tutti gli indirizzi BCC
     */
    public List<String> getBccAddresses() {
        return getEmailAddressesByType("BCC");
    }

    /**
     * Aggiunge un destinatario alla lista
     */
    public void addRecipient(String emailAddress, String recipientName, String recipientType, String createdBy) {
        // Verifica se già esiste
        EmailListRecipient existing = EmailListRecipient.findFirst(
            "id_list = ? AND email_address = ? AND recipient_type = ?",
            getId(), emailAddress, recipientType
        );

        if (existing != null) {
            // Riattiva se esistente ma inattivo
            if (!existing.getBoolean("is_active")) {
                existing.set("is_active", true);
                existing.saveIt();
            }
        } else {
            // Crea nuovo destinatario
            EmailListRecipient recipient = new EmailListRecipient();
            recipient.set("id_list", getId());
            recipient.set("email_address", emailAddress);
            recipient.set("recipient_name", recipientName);
            recipient.set("recipient_type", recipientType);
            recipient.set("is_active", true);
            recipient.set("created_by", createdBy);
            recipient.saveIt();
        }
    }

    /**
     * Rimuove un destinatario dalla lista
     */
    public void removeRecipient(String emailAddress, String recipientType) {
        EmailListRecipient recipient = EmailListRecipient.findFirst(
            "id_list = ? AND email_address = ? AND recipient_type = ?",
            getId(), emailAddress, recipientType
        );

        if (recipient != null) {
            // Hard delete per semplicità (non c'è soft delete su recipients)
            recipient.delete();
        }
    }

    /**
     * Conteggio totale destinatari attivi
     */
    public long getTotalRecipientCount() {
        Long count = EmailListRecipient.count(
            "id_list = ? AND is_active = TRUE",
            getId()
        );
        return count != null ? count : 0L;
    }

    /**
     * Conteggio destinatari per tipo
     */
    public Map<String, Long> getRecipientCountByType() {
        Map<String, Long> counts = new HashMap<>();

        Long toCount = EmailListRecipient.count(
            "id_list = ? AND recipient_type = 'TO' AND is_active = TRUE",
            getId()
        );
        Long ccCount = EmailListRecipient.count(
            "id_list = ? AND recipient_type = 'CC' AND is_active = TRUE",
            getId()
        );
        Long bccCount = EmailListRecipient.count(
            "id_list = ? AND recipient_type = 'BCC' AND is_active = TRUE",
            getId()
        );

        counts.put("TO", toCount != null ? toCount : 0L);
        counts.put("CC", ccCount != null ? ccCount : 0L);
        counts.put("BCC", bccCount != null ? bccCount : 0L);

        return counts;
    }

    /**
     * Ottiene i template associati a questa lista
     */
    public List<EmailTemplate> getAssociatedTemplates() {
        String sql = """
            SELECT t.*
            FROM email_templates t
            INNER JOIN template_email_lists tel ON tel.id_template = t.id_template
            WHERE tel.id_list = ?
              AND tel.is_active = TRUE
              AND t.is_deleted = FALSE
            ORDER BY t.template_name
            """;
        return EmailTemplate.findBySQL(sql, getId());
    }
}
