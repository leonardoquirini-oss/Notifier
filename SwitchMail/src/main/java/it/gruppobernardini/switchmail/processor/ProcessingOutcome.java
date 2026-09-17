package it.gruppobernardini.switchmail.processor;

import it.gruppobernardini.switchmail.util.JsonUtil;

import java.util.Map;

/**
 * L'esito di un processore. Il "nessun silent failure" reso strutturale.
 *
 * <p>Niente costruttore pubblico e un enum che non ammette fallimenti: <b>e' impossibile segnalare
 * un errore ritornando</b>. Ogni fallimento si lancia, e la pipeline lo classifica.
 *
 * <ul>
 *   <li>{@link #success} esige un oggetto estratto non-null: non si dichiara successo senza
 *       registrare <i>cosa</i> si e' estratto (finisce in extracted_json, visibile in UI).</li>
 *   <li>{@link #success} esige un messaggio non vuoto; {@link #skipped} esige una ragione non
 *       vuota - uno skip e' un evento consultabile, non un ritorno muto.</li>
 * </ul>
 */
public record ProcessingOutcome(
        Status status,
        String message,
        String extractedJson,
        String actionRef,
        Map<String, Object> details) {

    public enum Status {
        SUCCESS, SKIPPED
    }

    public ProcessingOutcome {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /**
     * @param extracted i dati estratti dalla mail, serializzati in extracted_json
     * @param actionRef riferimento all'azione eseguita su BERLink (id creato, path chiamato), se c'e'
     */
    public static ProcessingOutcome success(String message, Object extracted, String actionRef) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("ProcessingOutcome.success richiede un messaggio");
        }
        if (extracted == null) {
            throw new IllegalArgumentException(
                    "ProcessingOutcome.success richiede i dati estratti: un successo senza estrazione "
                            + "non e' verificabile da /logs");
        }
        return new ProcessingOutcome(Status.SUCCESS, message, JsonUtil.write(extracted), actionRef, Map.of());
    }

    public static ProcessingOutcome skipped(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("ProcessingOutcome.skipped richiede una ragione");
        }
        return new ProcessingOutcome(Status.SKIPPED, reason, null, null, Map.of());
    }

    public ProcessingOutcome withDetails(Map<String, Object> extra) {
        return new ProcessingOutcome(status, message, extractedJson, actionRef, extra);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
