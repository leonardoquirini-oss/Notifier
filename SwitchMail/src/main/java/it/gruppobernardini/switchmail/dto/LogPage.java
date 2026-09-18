package it.gruppobernardini.switchmail.dto;

import it.gruppobernardini.switchmail.model.ProcessingLogEntry;

import java.util.List;

/**
 * Una pagina di /logs con il cursore per la successiva.
 *
 * <p>Keyset e non OFFSET: la tabella cresce, e con OFFSET la pagina 50 costerebbe la scansione delle
 * 49 precedenti.
 */
public record LogPage(List<ProcessingLogEntry> rows, String nextCursorCreatedAt, Long nextCursorId) {

    public boolean hasMore() {
        return nextCursorId != null;
    }
}
