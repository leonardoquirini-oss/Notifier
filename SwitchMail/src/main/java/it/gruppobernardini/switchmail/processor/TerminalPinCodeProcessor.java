package it.gruppobernardini.switchmail.processor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pin code del terminal, dal body della mail.
 *
 * <p>Formato atteso (una etichetta per riga):
 * <pre>
 * Customer reference number: 6007
 * Gateway trip number: 00402600954188
 * ITU code: GBTU001673-5
 * Gate In Pin code: null
 * Gate Out Pin code: 185K2P6904
 * </pre>
 *
 * <p>Estrae tre valori e li normalizza come li vuole BERLink:
 * <ul>
 *   <li><b>BG</b> dal customer reference: prefisso configurabile + numero portato a 5 cifre
 *       ({@code 6007 -> 26A06007}, {@code 12345 -> 26A12345}).</li>
 *   <li><b>Container</b> dall'ITU code: zeri iniziali tolti dopo il prefisso alfabetico, e ogni
 *       carattere non alfanumerico prima dell'ultima cifra sostituito da un punto
 *       ({@code GBTU001673-5 -> GBTU1673.5}).</li>
 *   <li><b>Pin code</b> dalla riga di gate scelta nei parametri (di norma Gate Out).</li>
 * </ul>
 *
 * <p><b>Nessuna azione verso BERLink</b>: per ora scrive solo la riga
 * {@code [PROCESSOR-ACTION] BG : ... - Container : ... - Pin Code : ...} e registra i dati estratti
 * nel log di elaborazione, dove sono ispezionabili da /logs. Quando l'endpoint sara' definito,
 * l'azione si aggiunge qui sotto in {@code mode = PROCESS} e le mail gia' arrivate si rielaborano
 * con il bottone Riprova.
 */
@Component
@Slf4j
public class TerminalPinCodeProcessor extends AbstractMailSubProcessor {

    public static final String ID = "terminal-pin-codes";

    private static final String LABEL_CUSTOMER_REF = "Customer reference number";
    private static final String LABEL_ITU = "ITU code";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Pin code terminal (body della mail)";
    }

    @Override
    public String description() {
        return "Estrae BG, container e pin code dalle righe del body. Per ora registra soltanto: "
                + "nessuna scrittura su BERLink.";
    }

    @Override
    public List<ParamSpec> paramSpecs() {
        return List.of(
                ParamSpec.select("mode", "Modalita'", true, "EXTRACT", List.of("EXTRACT", "PROCESS"),
                        "EXTRACT: estrae e registra, senza agire. "
                                + "PROCESS: scrittura su BERLink (non ancora implementata)."),
                ParamSpec.string("bgPrefix", "Prefisso del BG", true, "26A",
                        "Anteposto al customer reference portato a 5 cifre: 6007 diventa 26A06007. "
                                + "Cambia con l'anno, per questo e' un parametro e non una costante."),
                ParamSpec.select("pinField", "Riga del pin code", true, "Gate Out Pin code",
                        List.of("Gate Out Pin code", "Gate In Pin code"),
                        "Quale delle due righe di gate contiene il pin da salvare."));
    }

    @Override
    protected ProcessingOutcome handle(MailContext ctx) {
        String body = requireBodyText(ctx);
        String prefix = ctx.params().getString("bgPrefix", "26A");
        String pinLabel = ctx.params().getString("pinField", "Gate Out Pin code");

        String customerRef = requireValue(body, LABEL_CUSTOMER_REF);
        String ituCode = requireValue(body, LABEL_ITU);

        String bg = bgNumber(customerRef, prefix);
        String container = containerNumber(ituCode);

        // Il pin puo' legittimamente mancare ("null" e' proprio cio' che scrive il terminal): non e'
        // un errore di formato, e' una mail senza niente da salvare. SKIPPED la rende consultabile
        // in /logs senza sporcare la dead-letter.
        Optional<String> pin = value(body, pinLabel);
        if (pin.isEmpty()) {
            return ProcessingOutcome.skipped("Nessun valore nella riga \"" + pinLabel + "\" (BG " + bg
                    + ", container " + container + "): niente da salvare.");
        }
        String pinCode = pin.get();

        log.info("[PROCESSOR-ACTION] BG : {} - Container : {} - Pin Code : {}", bg, container, pinCode);

        if ("PROCESS".equals(ctx.params().getString("mode", "EXTRACT"))) {
            throw terminal("NOT_IMPLEMENTED",
                    "scrittura del pin code su BERLink non ancora implementata: definire l'endpoint, "
                            + "aggiungere la chiamata e solo allora usare mode=PROCESS");
        }

        Map<String, Object> extracted = new LinkedHashMap<>();
        extracted.put("bg", bg);
        extracted.put("container", container);
        extracted.put("pinCode", pinCode);
        extracted.put("customerReferenceRaw", customerRef);
        extracted.put("ituCodeRaw", ituCode);
        extracted.put("pinField", pinLabel);

        return ProcessingOutcome.success(
                "BG " + bg + ", container " + container + ", pin " + pinCode
                        + " (estratti e registrati; nessuna azione su BERLink)",
                extracted, null);
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Il valore di una riga "Etichetta: valore".
     *
     * <p>Confronto sull'etichetta case-insensitive, e {@code null} letterale trattato come assente:
     * e' quello che il terminal scrive quando il pin non c'e'.
     */
    static Optional<String> value(String body, String label) {
        String needle = label.toLowerCase() + ":";
        for (String raw : body.split("\r?\n")) {
            String line = raw.trim();
            if (line.toLowerCase().startsWith(needle)) {
                String value = line.substring(needle.length()).trim();
                if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
                    return Optional.empty();
                }
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private String requireValue(String body, String label) {
        return value(body, label).orElseThrow(() -> terminal("BAD_FORMAT",
                "riga \"" + label + "\" assente o vuota nel body"));
    }

    /**
     * Customer reference -&gt; numero BG: prefisso + numero portato a 5 cifre.
     *
     * <p>Quattro cifre diventano cinque con uno zero davanti, cinque restano tali. Una lunghezza
     * diversa non e' un caso previsto: meglio una dead-letter leggibile che un BG inventato.
     */
    String bgNumber(String customerRef, String prefix) {
        String digits = customerRef.trim();
        if (!digits.matches("\\d{4,5}")) {
            throw terminal("BAD_FORMAT", "customer reference \"" + customerRef
                    + "\" non e' un numero di 4 o 5 cifre");
        }
        return prefix + "0".repeat(5 - digits.length()) + digits;
    }

    /**
     * ITU code -&gt; numero container.
     *
     * <p>Due regole: gli zeri che separano il prefisso alfabetico dalle cifre vengono tolti, e ogni
     * carattere non alfanumerico che precede l'ultima cifra diventa un punto.
     * {@code GBTU001673-5} diventa cosi' {@code GBTU1673.5}.
     *
     * <p>Gli spazi vengono rimossi prima di applicare le regole: un "GBTU 001673-5" diventerebbe
     * altrimenti "GBTU.001673.5", cioe' un container diverso da quello scritto nella mail.
     */
    String containerNumber(String ituCode) {
        String code = ituCode.replaceAll("\\s+", "");
        if (code.isEmpty()) {
            throw terminal("BAD_FORMAT", "ITU code vuoto");
        }

        // Zeri fra il prefisso alfabetico e il numero, anche se separati da un carattere non
        // alfanumerico ("GBTU-001673-5" -> "GBTU-1673-5"). Il lookahead impedisce di divorare
        // l'intero numero quando e' fatto di soli zeri.
        code = code.replaceFirst("^([A-Za-z]+)([^A-Za-z0-9]*)0+(?=\\d)", "$1$2");

        int lastDigit = -1;
        for (int i = code.length() - 1; i >= 0; i--) {
            if (Character.isDigit(code.charAt(i))) {
                lastDigit = i;
                break;
            }
        }
        if (lastDigit < 0) {
            throw terminal("BAD_FORMAT", "ITU code \"" + ituCode + "\" non contiene cifre");
        }

        StringBuilder out = new StringBuilder(code);
        for (int i = 0; i < lastDigit; i++) {
            if (!Character.isLetterOrDigit(out.charAt(i))) {
                out.setCharAt(i, '.');
            }
        }
        return out.toString();
    }
}
