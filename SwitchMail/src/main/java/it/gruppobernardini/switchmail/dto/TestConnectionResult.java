package it.gruppobernardini.switchmail.dto;

import java.util.List;

/**
 * Esito di "Testa connessione".
 *
 * <p>{@code openedMode} e' il campo che la UI mette in evidenza: "Cartella aperta in READ_ONLY -
 * nessun flag modificato". E' la rassicurazione per cui esiste tutta la modalita' B, e mostrarla
 * trasforma una promessa architetturale in un fatto osservabile.
 */
public record TestConnectionResult(
        boolean ok,
        String openedMode,
        String folder,
        Long uidValidity,
        Integer messageCount,
        Integer unreadCount,
        long durationMs,
        String error,
        List<MailPreview> lastMessages) {

    public static TestConnectionResult failure(String error, long durationMs) {
        return new TestConnectionResult(false, null, null, null, null, null, durationMs, error, List.of());
    }
}
