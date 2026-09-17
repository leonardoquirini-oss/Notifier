package it.gruppobernardini.switchmail.dto;

import it.gruppobernardini.switchmail.model.ProcessingStatus;

import java.util.List;

/**
 * Filtri della pagina /logs. Il preset di default e' "Da gestire" (DEAD_LETTER + NO_RULE): e' la
 * lista che dice cosa manca, non un elenco cronologico da scorrere.
 *
 * @param cursorCreatedAt paginazione keyset: (created_at, id) dell'ultima riga della pagina precedente
 */
public record LogFilter(
        Long accountId,
        List<ProcessingStatus> statuses,
        Long ruleId,
        String text,
        String fromIso,
        String toIso,
        String cursorCreatedAt,
        Long cursorId,
        int limit) {

    public static LogFilter daGestire() {
        return new LogFilter(null, List.of(ProcessingStatus.DEAD_LETTER, ProcessingStatus.NO_RULE),
                null, null, null, null, null, null, 50);
    }

    public LogFilter {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        if (limit <= 0 || limit > 200) {
            limit = 50;
        }
    }
}
