package it.gruppobernardini.switchmail.dto;

import it.gruppobernardini.switchmail.service.RecordingBerlinkApiClient.RecordedCall;

import java.util.List;

/**
 * Esito di un'esecuzione in prova: nulla viene scritto nel log e nessuna HTTP esce dal processo.
 *
 * @param calls le chiamate che il processore <i>avrebbe</i> fatto verso BERLink
 */
public record DryRunResult(
        String status,
        String message,
        String extractedJson,
        String actionRef,
        String errorType,
        Boolean retryable,
        List<RecordedCall> calls) {

    public DryRunResult {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }
}
