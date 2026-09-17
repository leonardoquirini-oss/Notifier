package it.gruppobernardini.switchmail;

import it.gruppobernardini.switchmail.processor.ParamSpec;
import it.gruppobernardini.switchmail.processor.ProcessorParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessorParamsTest {

    private static final List<ParamSpec> SPECS = List.of(
            ParamSpec.select("mode", "Modalita'", true, "COLLECT", List.of("COLLECT", "PROCESS"), null),
            ParamSpec.string("attachmentPattern", "Allegato", true, "(?i).*\\.txt$", null),
            ParamSpec.integer("maxRighe", "Righe massime", false, "100", null),
            ParamSpec.bool("strict", "Rigoroso", false, "false", null),
            ParamSpec.json("mapping", "Mappatura", false, null, null));

    @Test
    @DisplayName("i default si applicano quando il valore manca o e' vuoto")
    void defaults() {
        ProcessorParams p = ProcessorParams.of(Map.of(), SPECS);
        assertThat(p.requireString("mode")).isEqualTo("COLLECT");
        assertThat(p.getInt("maxRighe", -1)).isEqualTo(100);
        assertThat(p.getBool("strict", true)).isFalse();

        ProcessorParams blank = ProcessorParams.of(Map.of("mode", "   "), SPECS);
        assertThat(blank.requireString("mode")).isEqualTo("COLLECT");
    }

    @Test
    @DisplayName("un parametro obbligatorio senza default e senza valore nomina la chiave")
    void requiredMancante() {
        List<ParamSpec> specs = List.of(ParamSpec.string("endpoint", "Endpoint", true, null, null));
        assertThatThrownBy(() -> ProcessorParams.of(Map.of(), specs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    @DisplayName("chiave sconosciuta rifiutata: un parametro che nessuno legge e' una bugia nella UI")
    void chiaveSconosciuta() {
        assertThatThrownBy(() -> ProcessorParams.of(Map.of("train_no", "4521"), SPECS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parametro sconosciuto: 'train_no'")
                .hasMessageContaining("previsti:");
    }

    @Test
    void coercizioneDeiTipi() {
        ProcessorParams p = ProcessorParams.of(Map.of(
                "mode", "PROCESS",
                "attachmentPattern", ".*\\.csv$",
                "maxRighe", "250",
                "strict", "on"), SPECS);
        assertThat(p.requireInt("maxRighe")).isEqualTo(250);
        assertThat(p.getBool("strict", false)).isTrue();
        assertThat(p.asMap().get("maxRighe")).isInstanceOf(Integer.class);
    }

    @Test
    void interoNonValido() {
        assertThatThrownBy(() -> ProcessorParams.of(Map.of("maxRighe", "molte"), SPECS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("atteso un intero");
    }

    @Test
    @DisplayName("SELECT: un valore fuori dalle options non passa")
    void selectFuoriDaOptions() {
        assertThatThrownBy(() -> ProcessorParams.of(Map.of("mode", "TURBO"), SPECS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non ammesso")
                .hasMessageContaining("COLLECT, PROCESS");
    }

    @Test
    @DisplayName("JSON: il blob viene validato, non interpretato")
    void jsonValidato() {
        ProcessorParams ok = ProcessorParams.of(Map.of("mapping", "{\"a\":1}"), SPECS);
        assertThat(ok.requireString("mapping")).isEqualTo("{\"a\":1}");

        assertThatThrownBy(() -> ProcessorParams.of(Map.of("mapping", "{non json"), SPECS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON non valido");
    }

    @Test
    void ofJson() {
        ProcessorParams p = ProcessorParams.ofJson("{\"mode\":\"PROCESS\"}", SPECS);
        assertThat(p.requireString("mode")).isEqualTo("PROCESS");
        assertThat(ProcessorParams.ofJson(null, SPECS).requireString("mode")).isEqualTo("COLLECT");
    }

    @Test
    void asMapEImmutabile() {
        ProcessorParams p = ProcessorParams.of(Map.of(), SPECS);
        assertThatThrownBy(() -> p.asMap().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }
}
