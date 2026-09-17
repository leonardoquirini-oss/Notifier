package it.gruppobernardini.switchmail.processor;

/**
 * Base delle eccezioni classificate. Il codice {@code errorType} e' una costante stabile
 * (BAD_FORMAT, BERLINK_5XX, UNKNOWN_PROCESSOR, ...): finisce in mail_processing_log.error_type,
 * si filtra dalla UI e non cambia quando cambia il testo del messaggio.
 */
public abstract class MailProcessingException extends RuntimeException {

    private final String errorType;

    protected MailProcessingException(String errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public String errorType() {
        return errorType;
    }

    public abstract boolean retryable();
}
