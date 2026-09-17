package it.gruppobernardini.switchmail.processor;

import it.gruppobernardini.switchmail.model.RuleConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Avvisi di partenza treno: i dati stanno in un allegato .txt.
 *
 * <p><b>Stub deliberato.</b> Il formato del .txt non e' ancora definito, quindi il processore parte
 * in modalita' COLLECT: acquisisce l'allegato, ne registra la dimensione e lascia che la pipeline
 * archivi il MIME grezzo, senza agire. Cosi' la fase di stub e' anche la fase di scoperta del
 * formato: dopo una settimana ci sono .eml veri, scaricabili da /logs, da mettere in
 * src/test/resources/mail/ come fixture.
 *
 * <p>Uno stub che ritorna success sarebbe una bugia; uno che lancia sempre riempirebbe la
 * dead-letter di rumore. Il parametro "mode" e' la terza via.
 */
@Component
public class TrainDepartureProcessor extends AbstractMailSubProcessor {

    public static final String ID = "train-departure";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Avvisi partenza treno (allegato .txt)";
    }

    @Override
    public String description() {
        return "Estrae numero treno e data dall'allegato .txt. Formato non ancora definito: "
                + "in modalita' COLLECT archivia la mail per l'analisi.";
    }

    @Override
    public List<ParamSpec> paramSpecs() {
        return List.of(
                ParamSpec.select("mode", "Modalita'", true, "COLLECT", List.of("COLLECT", "PROCESS"),
                        "COLLECT: archivia la mail per analisi senza agire. "
                                + "PROCESS: parsing reale (non ancora implementato)."),
                ParamSpec.string("attachmentPattern", "Allegato (regex sul nome file)", true,
                        "(?i).*\\.txt$", "Semantica substring: usa ^...$ per ancorare."),
                ParamSpec.select("dateFormat", "Formato data nell'oggetto", true, "dd/MM/yyyy",
                        List.of("dd/MM/yyyy", "yyyy-MM-dd"), null));
    }

    @Override
    public void validateRule(RuleConfig rule, ProcessorParams params) {
        if (!rule.requireAttachment()) {
            throw new IllegalArgumentException(
                    "Questo processore lavora su un allegato: abilita 'Richiedi allegato' nella regola.");
        }
    }

    @Override
    protected ProcessingOutcome handle(MailContext ctx) {
        String text = requireAttachmentText(ctx, ctx.params().requireString("attachmentPattern"));

        if ("COLLECT".equals(ctx.params().getString("mode", "COLLECT"))) {
            return ProcessingOutcome.skipped("Modalita' COLLECT: allegato acquisito ("
                    + text.length() + " caratteri, " + lines(text).size() + " righe), "
                    + "formato non ancora definito. MIME grezzo archiviato per l'analisi.");
        }

        throw terminal("NOT_IMPLEMENTED",
                "parsing del .txt di partenza treno non ancora implementato: definire il formato, "
                        + "scrivere il parser e solo allora passare mode=PROCESS");
    }
}
