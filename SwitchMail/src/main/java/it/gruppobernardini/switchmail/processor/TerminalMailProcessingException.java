package it.gruppobernardini.switchmail.processor;

/**
 * Fallimento che non migliora ritentando: formato illeggibile, 4xx, bug nel parser.
 *
 * <p>E' anche il default della pipeline per le eccezioni non classificate. Scelta contraria alla
 * saggezza comune, e motivata: una NPE in un parser non guarisce al terzo tentativo, ma fa partire
 * tre chiamate a BERLink e tre notifiche. Nulla va perso comunque - il MIME grezzo e' archiviato,
 * la riga sta nella dead-letter e /logs ha il bottone Riprova.
 */
public class TerminalMailProcessingException extends MailProcessingException {

    public TerminalMailProcessingException(String errorType, String message, Throwable cause) {
        super(errorType, message, cause);
    }

    @Override
    public boolean retryable() {
        return false;
    }
}
