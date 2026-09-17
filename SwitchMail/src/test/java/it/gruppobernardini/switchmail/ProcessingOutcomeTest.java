package it.gruppobernardini.switchmail;

import it.gruppobernardini.switchmail.processor.ProcessingOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessingOutcomeTest {

    @Test
    @DisplayName("l'enum non ha una costante di fallimento: un errore non si puo' ritornare")
    void nessunFallimentoRitornabile() {
        assertThat(ProcessingOutcome.Status.values())
                .containsExactly(ProcessingOutcome.Status.SUCCESS, ProcessingOutcome.Status.SKIPPED);
    }

    @Test
    @DisplayName("successo senza dati estratti = successo non verificabile da /logs: vietato")
    void successRichiedeEstratto() {
        assertThatThrownBy(() -> ProcessingOutcome.success("fatto", null, "ref"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dati estratti");
    }

    @Test
    void successRichiedeMessaggio() {
        assertThatThrownBy(() -> ProcessingOutcome.success("  ", Map.of("a", 1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messaggio");
    }

    @Test
    @DisplayName("uno skip e' un evento consultabile, non un ritorno muto")
    void skippedRichiedeRagione() {
        assertThatThrownBy(() -> ProcessingOutcome.skipped(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ragione");
    }

    @Test
    void successSerializzaIDatiEstratti() {
        ProcessingOutcome out = ProcessingOutcome.success("treno 4521 registrato",
                Map.of("treno", "4521"), "/api/bookings/99");
        assertThat(out.isSuccess()).isTrue();
        assertThat(out.extractedJson()).isEqualTo("{\"treno\":\"4521\"}");
        assertThat(out.actionRef()).isEqualTo("/api/bookings/99");
    }
}
