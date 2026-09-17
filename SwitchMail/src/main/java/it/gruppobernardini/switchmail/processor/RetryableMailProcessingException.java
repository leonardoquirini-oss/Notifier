package it.gruppobernardini.switchmail.processor;

/** Fallimento che ha senso ritentare: 5xx, timeout, dipendenza non ancora disponibile. */
public class RetryableMailProcessingException extends MailProcessingException {

    public RetryableMailProcessingException(String errorType, String message, Throwable cause) {
        super(errorType, message, cause);
    }

    @Override
    public boolean retryable() {
        return true;
    }
}
