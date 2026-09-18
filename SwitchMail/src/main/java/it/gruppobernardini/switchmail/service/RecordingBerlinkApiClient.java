package it.gruppobernardini.switchmail.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Il client del dry-run: registra le chiamate e non ne fa nessuna.
 *
 * <p>E' la meta' concreta della garanzia di /ruletest: un processore non puo' fisicamente scrivere su
 * BERLink dalla schermata di test, perche' il contesto gli consegna questa implementazione e non ha
 * altro modo di raggiungere la rete. La UI mostra esattamente le chiamate registrate qui.
 */
@Slf4j
public class RecordingBerlinkApiClient implements BerlinkApiClient {

    /** Gli header senza la API key: una schermata di test non deve mostrare segreti. */
    public record RecordedCall(String method, String path, Map<String, String> headers, Object body) {
    }

    private final List<RecordedCall> calls = new ArrayList<>();
    private final String idempotencyKey;

    public RecordingBerlinkApiClient(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    @Override
    public BerlinkResponse post(String path, Object body) {
        return record("POST", path, body);
    }

    @Override
    public BerlinkResponse get(String path) {
        return record("GET", path, null);
    }

    private BerlinkResponse record(String method, String path, Object body) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-API-Key", "(non mostrata)");
        if (idempotencyKey != null && !"GET".equals(method)) {
            headers.put("X-Idempotency-Key", idempotencyKey);
        }
        calls.add(new RecordedCall(method, path, headers, body));
        log.debug("Dry-run: {} {} non eseguita", method, path);

        // 200 con corpo vuoto: il processore prosegue come se la chiamata fosse riuscita, cosi' il
        // dry-run mostra l'intera sequenza e non si ferma alla prima risposta mancante.
        return new BerlinkResponse(200, "", Map.of());
    }

    public List<RecordedCall> calls() {
        return List.copyOf(calls);
    }
}
