package it.gruppobernardini.switchmail.model;

/**
 * Stato di una mail nel registro.
 *
 * <p>NO_RULE non e' rumore: finche' i formati sono ignoti, "le mail che nessuno ha gestito" e' la
 * lista che dice quali regole scrivere. /logs parte filtrato su DEAD_LETTER + NO_RULE.
 */
public enum ProcessingStatus {
    IN_PROGRESS, SUCCESS, SKIPPED, NO_RULE, RETRY_SCHEDULED, DEAD_LETTER, RESOLVED;

    public static ProcessingStatus of(String value) {
        return value == null ? null : valueOf(value);
    }
}
