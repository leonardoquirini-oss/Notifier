package it.gruppobernardini.switchmail.processor;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Forecast di terminal: i dati stanno nel body della mail.
 *
 * <p>Stesso stub deliberato di {@link TrainDepartureProcessor}, ma sul body invece che
 * sull'allegato: in COLLECT acquisisce il testo e lascia archiviare il MIME grezzo.
 */
@Component
public class TerminalForecastProcessor extends AbstractMailSubProcessor {

    public static final String ID = "terminal-forecast";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Forecast terminal (body della mail)";
    }

    @Override
    public String description() {
        return "Estrae terminal e identificativo dal body. Formato non ancora definito: "
                + "in modalita' COLLECT archivia la mail per l'analisi.";
    }

    @Override
    public List<ParamSpec> paramSpecs() {
        return List.of(
                ParamSpec.select("mode", "Modalita'", true, "COLLECT", List.of("COLLECT", "PROCESS"),
                        "COLLECT: archivia la mail per analisi senza agire. "
                                + "PROCESS: parsing reale (non ancora implementato)."),
                ParamSpec.string("terminalPattern", "Terminal (regex sull'oggetto)", false,
                        "(?i)INFO:\\s*(\\S+)", "Il gruppo 1 e' il codice terminal."));
    }

    @Override
    protected ProcessingOutcome handle(MailContext ctx) {
        String body = requireBodyText(ctx);

        if ("COLLECT".equals(ctx.params().getString("mode", "COLLECT"))) {
            return ProcessingOutcome.skipped("Modalita' COLLECT: body acquisito ("
                    + body.length() + " caratteri, " + lines(body).size() + " righe), "
                    + "formato non ancora definito. MIME grezzo archiviato per l'analisi.");
        }

        throw terminal("NOT_IMPLEMENTED",
                "parsing del body forecast non ancora implementato: definire il formato, "
                        + "scrivere il parser e solo allora passare mode=PROCESS");
    }
}
