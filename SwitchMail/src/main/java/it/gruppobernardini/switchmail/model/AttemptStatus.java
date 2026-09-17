package it.gruppobernardini.switchmail.model;

/** Esito di un singolo tentativo (audit append-only). */
public enum AttemptStatus {
    SUCCESS, SKIPPED, FAILED_RETRYABLE, FAILED_TERMINAL
}
