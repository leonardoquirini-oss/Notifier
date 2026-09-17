package it.gruppobernardini.switchmail;

import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.RuleConfig;
import it.gruppobernardini.switchmail.processor.MailContext;
import it.gruppobernardini.switchmail.processor.ProcessingOutcome;
import it.gruppobernardini.switchmail.processor.ProcessorParams;
import it.gruppobernardini.switchmail.processor.TerminalForecastProcessor;
import it.gruppobernardini.switchmail.processor.TerminalMailProcessingException;
import it.gruppobernardini.switchmail.processor.TrainDepartureProcessor;
import it.gruppobernardini.switchmail.support.MailFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gli stub non sono codice morto: in COLLECT devono acquisire e dichiararlo, e in PROCESS devono
 * fallire in modo terminale invece di fingere un successo.
 */
class StubProcessorsTest {

    private final TrainDepartureProcessor train = new TrainDepartureProcessor();
    private final TerminalForecastProcessor forecast = new TerminalForecastProcessor();

    private MailContext ctx(ParsedMail mail, Object processor, Map<String, Object> raw) {
        RuleConfig rule = MailFixtures.simpleRule("x", "{}", true);
        ProcessorParams params = processor == train
                ? ProcessorParams.of(raw, train.paramSpecs())
                : ProcessorParams.of(raw, forecast.paramSpecs());
        return new MailContext(mail, rule, params, 1, false, null);
    }

    @Test
    @DisplayName("train COLLECT: allegato acquisito, esito SKIPPED con la ragione leggibile")
    void trainCollect() {
        ParsedMail mail = MailFixtures.mail("a@ferrovie.it", "AVVISI PARTENZA TRENO 4521 17/09/2026", "",
                MailFixtures.txt("treno.txt", "riga 1\nriga 2\n"));

        ProcessingOutcome out = train.process(ctx(mail, train, Map.of()));

        assertThat(out.status()).isEqualTo(ProcessingOutcome.Status.SKIPPED);
        assertThat(out.message()).contains("COLLECT").contains("caratteri").contains("MIME grezzo archiviato");
    }

    @Test
    @DisplayName("train PROCESS: terminale NOT_IMPLEMENTED, non un finto successo")
    void trainProcessNonImplementato() {
        ParsedMail mail = MailFixtures.mail("a@ferrovie.it", "AVVISI PARTENZA TRENO 4521",
                "", MailFixtures.txt("treno.txt", "dati"));

        assertThatThrownBy(() -> train.process(ctx(mail, train, Map.of("mode", "PROCESS"))))
                .isInstanceOf(TerminalMailProcessingException.class)
                .extracting(e -> ((TerminalMailProcessingException) e).errorType())
                .isEqualTo("NOT_IMPLEMENTED");
    }

    @Test
    @DisplayName("allegato assente: terminale che elenca gli allegati presenti")
    void allegatoAssente() {
        ParsedMail mail = MailFixtures.mail("a@ferrovie.it", "AVVISI", "",
                MailFixtures.txt("listato.pdf", "x"));

        assertThatThrownBy(() -> train.process(ctx(mail, train, Map.of())))
                .isInstanceOf(TerminalMailProcessingException.class)
                .hasMessageContaining("nessun allegato corrisponde")
                .hasMessageContaining("listato.pdf");
    }

    @Test
    @DisplayName("piu' allegati che matchano: ambiguo, non si sceglie a caso")
    void allegatoAmbiguo() {
        ParsedMail mail = MailFixtures.mail("a@ferrovie.it", "AVVISI", "",
                MailFixtures.txt("treno1.txt", "x"), MailFixtures.txt("treno2.txt", "y"));

        assertThatThrownBy(() -> train.process(ctx(mail, train, Map.of())))
                .isInstanceOf(TerminalMailProcessingException.class)
                .hasMessageContaining("piu' allegati corrispondono");
    }

    @Test
    @DisplayName("allegato troncato: non si fa parsing su dati incompleti")
    void allegatoTroncato() {
        ParsedMail mail = MailFixtures.mail("a@ferrovie.it", "AVVISI", "",
                MailFixtures.truncated("treno.txt", "meta' dei dati"));

        assertThatThrownBy(() -> train.process(ctx(mail, train, Map.of())))
                .isInstanceOf(TerminalMailProcessingException.class)
                .extracting(e -> ((TerminalMailProcessingException) e).errorType())
                .isEqualTo("ATTACHMENT_TRUNCATED");
    }

    @Test
    @DisplayName("la regola senza 'Richiedi allegato' viene rifiutata al salvataggio")
    void validateRule() {
        RuleConfig senzaAllegato = MailFixtures.simpleRule(TrainDepartureProcessor.ID, "{}", false);
        assertThatThrownBy(() -> train.validateRule(senzaAllegato,
                ProcessorParams.of(Map.of(), train.paramSpecs())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Richiedi allegato");

        RuleConfig conAllegato = MailFixtures.simpleRule(TrainDepartureProcessor.ID, "{}", true);
        train.validateRule(conAllegato, ProcessorParams.of(Map.of(), train.paramSpecs()));
    }

    @Test
    @DisplayName("forecast COLLECT lavora sul body; body vuoto = terminale EMPTY_BODY")
    void forecastCollect() {
        ParsedMail conBody = MailFixtures.mail("b@terminal.it", "FORECAST OF TERMINAL / INFO: GE 88", "riga\nriga");
        ProcessingOutcome out = forecast.process(ctx(conBody, forecast, Map.of()));
        assertThat(out.status()).isEqualTo(ProcessingOutcome.Status.SKIPPED);

        ParsedMail senzaBody = MailFixtures.mail("b@terminal.it", "FORECAST", "");
        assertThatThrownBy(() -> forecast.process(ctx(senzaBody, forecast, Map.of())))
                .isInstanceOf(TerminalMailProcessingException.class)
                .extracting(e -> ((TerminalMailProcessingException) e).errorType())
                .isEqualTo("EMPTY_BODY");
    }

    @Test
    @DisplayName("una RuntimeException qualunque diventa terminale UNEXPECTED, mai un successo silenzioso")
    void eccezioneNonClassificata() {
        var rogue = new it.gruppobernardini.switchmail.processor.AbstractMailSubProcessor() {
            @Override
            public String id() {
                return "rogue";
            }

            @Override
            public String displayName() {
                return "Rogue";
            }

            @Override
            protected ProcessingOutcome handle(MailContext ctx) {
                throw new IllegalStateException("boom");
            }
        };
        ParsedMail mail = MailFixtures.mail("a@b.it", "x", "y");
        MailContext c = new MailContext(mail, MailFixtures.simpleRule("rogue", "{}", false),
                ProcessorParams.empty(), 1, false, null);

        assertThatThrownBy(() -> rogue.process(c))
                .isInstanceOf(TerminalMailProcessingException.class)
                .extracting(e -> ((TerminalMailProcessingException) e).errorType())
                .isEqualTo("UNEXPECTED");
    }

    @Test
    @DisplayName("un handle che ritorna null non passa per successo")
    void outcomeNull() {
        var nullo = new it.gruppobernardini.switchmail.processor.AbstractMailSubProcessor() {
            @Override
            public String id() {
                return "nullo";
            }

            @Override
            public String displayName() {
                return "Nullo";
            }

            @Override
            protected ProcessingOutcome handle(MailContext ctx) {
                return null;
            }
        };
        MailContext c = new MailContext(MailFixtures.mail("a@b.it", "x", "y"),
                MailFixtures.simpleRule("nullo", "{}", false), ProcessorParams.empty(), 1, false, null);

        assertThatThrownBy(() -> nullo.process(c))
                .isInstanceOf(TerminalMailProcessingException.class)
                .extracting(e -> ((TerminalMailProcessingException) e).errorType())
                .isEqualTo("NULL_OUTCOME");
    }

    @Test
    void paramSpecsCoerentiConIParametriLetti() {
        assertThat(train.paramSpecs()).extracting(s -> s.key())
                .containsExactly("mode", "attachmentPattern", "dateFormat");
        assertThat(forecast.paramSpecs()).extracting(s -> s.key()).contains("mode");
        assertThat(List.of(train.id(), forecast.id())).containsExactly("train-departure", "terminal-forecast");
    }
}
