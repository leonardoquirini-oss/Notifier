package it.gruppobernardini.switchmail.processor;

import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.support.MailFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TerminalPinCodeProcessorTest {

    private final TerminalPinCodeProcessor processor = new TerminalPinCodeProcessor();

    /** Il body reale, virgole e disclaimer compresi. */
    private static final String BODY = """
            Customer reference number: 6007
            Gateway trip number: 00402600954188
            ITU code: GBTU001673-5
            Gate In Pin code: null
            Gate Out Pin code: 185K2P6904
            La presente e-mail e' stata generata automaticamente da un indirizzo di posta elettronica
            di solo invio; si chiede pertanto di non rispondere al messaggio.
            """;

    private ProcessingOutcome run(String body, Map<String, Object> params) {
        ParsedMail mail = MailFixtures.mail("terminal@operatore.it",
                "New terminal pin codes for booking 6007 - GENOA PSA", body);
        MailContext ctx = new MailContext(mail,
                MailFixtures.simpleRule(TerminalPinCodeProcessor.ID, "{}", false),
                ProcessorParams.of(params, processor.paramSpecs()), 1, false, null);
        return processor.process(ctx);
    }

    @Test
    @DisplayName("il body di esempio produce BG, container e pin attesi")
    void estrazioneCompleta() {
        ProcessingOutcome outcome = run(BODY, Map.of());

        assertThat(outcome.status()).isEqualTo(ProcessingOutcome.Status.SUCCESS);
        assertThat(outcome.extractedJson())
                .contains("\"bg\":\"26A06007\"")
                .contains("\"container\":\"GBTU1673.5\"")
                .contains("\"pinCode\":\"185K2P6904\"");
        assertThat(outcome.message()).contains("26A06007").contains("GBTU1673.5").contains("185K2P6904");
    }

    @Test
    @DisplayName("customer reference: 4 cifre paddate, 5 cifre invariate, altro rifiutato")
    void numeroBg() {
        assertThat(processor.bgNumber("6007", "26A")).isEqualTo("26A06007");
        assertThat(processor.bgNumber("12345", "26A")).isEqualTo("26A12345");
        assertThat(processor.bgNumber(" 0042 ", "26A")).isEqualTo("26A00042");
        assertThat(processor.bgNumber("6007", "27A")).isEqualTo("27A06007");

        for (String bad : new String[]{"600", "123456", "60A7", ""}) {
            assertThatThrownBy(() -> processor.bgNumber(bad, "26A"))
                    .as("customer reference '%s'", bad)
                    .isInstanceOf(TerminalMailProcessingException.class)
                    .hasMessageContaining("4 o 5 cifre");
        }
    }

    @Test
    @DisplayName("ITU code: zeri iniziali tolti, non alfanumerici prima dell'ultima cifra a punto")
    void numeroContainer() {
        assertThat(processor.containerNumber("GBTU001673-5")).isEqualTo("GBTU1673.5");
        assertThat(processor.containerNumber("MSCU000123-4")).isEqualTo("MSCU123.4");
        assertThat(processor.containerNumber("ABCD1234567")).isEqualTo("ABCD1234567");
        assertThat(processor.containerNumber("GBTU 001673-5")).isEqualTo("GBTU1673.5");
        assertThat(processor.containerNumber("GBTU001673/5")).isEqualTo("GBTU1673.5");
        assertThat(processor.containerNumber("GBTU-001673-5")).isEqualTo("GBTU.1673.5");
        // Degenere ma definito: di un numero di soli zeri ne resta uno, non zero cifre.
        assertThat(processor.containerNumber("GBTU000000-1")).isEqualTo("GBTU0.1");
    }

    @Test
    void ituCodeSenzaCifreOAssente() {
        assertThatThrownBy(() -> processor.containerNumber("SOLOLETTERE"))
                .isInstanceOf(TerminalMailProcessingException.class)
                .hasMessageContaining("non contiene cifre");

        assertThatThrownBy(() -> run("Customer reference number: 6007\nGate Out Pin code: 1234", Map.of()))
                .isInstanceOf(TerminalMailProcessingException.class)
                .hasMessageContaining("ITU code");
    }

    @Test
    @DisplayName("pin assente o 'null': SKIPPED consultabile, non dead-letter")
    void pinAssente() {
        String body = BODY.replace("Gate Out Pin code: 185K2P6904", "Gate Out Pin code: null");

        ProcessingOutcome outcome = run(body, Map.of());

        assertThat(outcome.status()).isEqualTo(ProcessingOutcome.Status.SKIPPED);
        assertThat(outcome.message()).contains("Gate Out Pin code").contains("26A06007");
    }

    @Test
    @DisplayName("si puo' leggere il pin di ingresso invece che quello di uscita")
    void pinDiIngresso() {
        String body = BODY.replace("Gate In Pin code: null", "Gate In Pin code: AAA111");

        ProcessingOutcome outcome = run(body, Map.of("pinField", "Gate In Pin code"));

        assertThat(outcome.extractedJson()).contains("\"pinCode\":\"AAA111\"");
    }

    @Test
    @DisplayName("customer reference mancante: terminale, non un BG inventato")
    void customerReferenceMancante() {
        String body = BODY.replace("Customer reference number: 6007", "Customer reference number:");

        assertThatThrownBy(() -> run(body, Map.of()))
                .isInstanceOf(TerminalMailProcessingException.class)
                .hasMessageContaining("Customer reference number");
    }

    @Test
    @DisplayName("etichette riconosciute a prescindere da maiuscole e spazi attorno")
    void etichetteTolleranti() {
        String body = "  CUSTOMER REFERENCE NUMBER:   6007  \n  itu code:  GBTU001673-5\n"
                + "  Gate Out Pin code:   185K2P6904   \n";

        ProcessingOutcome outcome = run(body, Map.of());

        assertThat(outcome.extractedJson()).contains("\"bg\":\"26A06007\"").contains("\"pinCode\":\"185K2P6904\"");
    }

    @Test
    @DisplayName("mode=PROCESS: terminale finche' l'azione non esiste, mai un finto successo")
    void processNonImplementato() {
        assertThatThrownBy(() -> run(BODY, Map.of("mode", "PROCESS")))
                .isInstanceOf(TerminalMailProcessingException.class)
                .extracting(e -> ((TerminalMailProcessingException) e).errorType())
                .isEqualTo("NOT_IMPLEMENTED");
    }
}
