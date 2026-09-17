package it.gruppobernardini.switchmail.service;

import java.util.Map;

/**
 * L'unica uscita di rete di un sub-processore.
 *
 * <p>Un'istanza e' <b>legata a una mail</b> (MailContext): la chiave di idempotenza e la modalita'
 * dry-run vengono da li', non da chi chiama. Cosi' l'autore di un processore scrive
 * {@code api(ctx).post(...)} e ottiene gratis:
 * <ul>
 *   <li>header X-API-Key verso BERLink,</li>
 *   <li>header X-Idempotency-Key derivato dall'identita' della mail,</li>
 *   <li>la classificazione degli errori HTTP in Retryable/Terminal - la sede piu' autorevole delle
 *       tre, perche' la maggior parte dei processori fallira' solo su HTTP.</li>
 * </ul>
 *
 * <p>Implementazioni: quella vera e una che registra le chiamate senza farne nessuna (dry-run).
 */
public interface BerlinkApiClient {

    BerlinkResponse post(String path, Object body);

    BerlinkResponse get(String path);

    /**
     * @param json corpo gia' deserializzato quando la risposta e' un oggetto JSON, altrimenti vuoto
     */
    record BerlinkResponse(int status, String rawBody, Map<String, Object> json) {

        public BerlinkResponse {
            json = json == null ? Map.of() : Map.copyOf(json);
        }

        public Object value(String key) {
            return json.get(key);
        }

        /** BERLink risponde con l'involucro {"success": true, "data": {...}}. */
        @SuppressWarnings("unchecked")
        public Map<String, Object> data() {
            Object data = json.get("data");
            return data instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }
    }
}
