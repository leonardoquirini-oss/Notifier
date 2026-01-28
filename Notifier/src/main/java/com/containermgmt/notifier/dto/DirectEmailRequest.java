package com.containermgmt.notifier.dto;

import lombok.Data;

import java.util.List;

/**
 * DTO per richieste email dirette (senza template).
 * Usato per l'evento email:send che invia email direttamente dal payload.
 */
@Data
public class DirectEmailRequest {

    /**
     * Indirizzo mittente (opzionale, usa default se assente)
     */
    private String from;

    /**
     * Nome mittente (opzionale, usa default se assente)
     */
    private String senderName;

    /**
     * Lista destinatari principali (obbligatorio)
     */
    private List<String> to;

    /**
     * Lista destinatari in copia conoscenza (opzionale)
     */
    private List<String> cc;

    /**
     * Lista destinatari in copia nascosta (opzionale)
     */
    private List<String> ccn;

    /**
     * Oggetto email (obbligatorio)
     */
    private String subject;

    /**
     * Corpo email (obbligatorio)
     */
    private String body;

    /**
     * Se true, il body è HTML; se false, plain text.
     * Default: auto-detect basato sulla presenza di tag HTML nel body
     */
    private boolean isHtml;

    /**
     * Lista ID degli allegati da scaricare e allegare
     */
    private List<Integer> attachments;

    /**
     * Se true, elimina gli allegati dopo l'invio email con successo
     */
    private boolean deleteAttachments;
}
