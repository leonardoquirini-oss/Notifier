package com.containermgmt.notifier.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * DTO per richieste email dirette (senza template).
 * Usato sia per l'evento stream email:send sia per l'endpoint REST POST /email.
 * I nomi JSON sono in snake_case per coerenza col payload dello stream.
 */
@Data
public class DirectEmailRequest {

    /**
     * Indirizzo mittente (opzionale, usa default se assente)
     */
    @JsonProperty("from")
    private String from;

    /**
     * Nome mittente (opzionale, usa default se assente)
     */
    @JsonProperty("sender_name")
    private String senderName;

    /**
     * Lista destinatari principali (obbligatorio)
     */
    @JsonProperty("to")
    private List<String> to;

    /**
     * Lista destinatari in copia conoscenza (opzionale)
     */
    @JsonProperty("cc")
    private List<String> cc;

    /**
     * Lista destinatari in copia nascosta (opzionale)
     */
    @JsonProperty("ccn")
    private List<String> ccn;

    /**
     * Oggetto email (obbligatorio)
     */
    @JsonProperty("subject")
    private String subject;

    /**
     * Corpo email (obbligatorio)
     */
    @JsonProperty("body")
    private String body;

    /**
     * Se true, il body è HTML; se false, plain text.
     * Default: auto-detect basato sulla presenza di tag HTML nel body
     */
    @JsonProperty("is_html")
    private boolean isHtml;

    /**
     * Lista ID degli allegati da scaricare e allegare
     */
    @JsonProperty("attachments")
    private List<Integer> attachments;

    /**
     * Se true, elimina gli allegati dopo l'invio email con successo
     */
    @JsonProperty("delete_attachments")
    private boolean deleteAttachments;
}
