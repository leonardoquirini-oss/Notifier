package it.gruppobernardini.switchmail;

import it.gruppobernardini.switchmail.model.FieldCheck;
import it.gruppobernardini.switchmail.model.FieldCheck.MatchMode;
import it.gruppobernardini.switchmail.util.RegexUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldCheckTest {

    @Test
    @DisplayName("pattern vuoto o null = wildcard, matcha tutto (anche null)")
    void wildcard() {
        assertThat(FieldCheck.wildcard().isWildcard()).isTrue();
        assertThat(FieldCheck.wildcard().matches("qualsiasi")).isTrue();
        assertThat(FieldCheck.wildcard().matches(null)).isTrue();
        assertThat(new FieldCheck("   ", MatchMode.EQUALS, false).isWildcard()).isTrue();
    }

    @Test
    @DisplayName("un valore null non matcha mai: oggetto assente non e' oggetto qualsiasi")
    void nullValue() {
        assertThat(new FieldCheck("x", MatchMode.CONTAINS, false).matches(null)).isFalse();
        assertThat(new FieldCheck("x", MatchMode.EQUALS, false).matches(null)).isFalse();
        assertThat(new FieldCheck("x", MatchMode.REGEX, false).matches(null)).isFalse();
    }

    @Test
    void equalsMode() {
        FieldCheck ci = new FieldCheck("FORECAST", MatchMode.EQUALS, false);
        assertThat(ci.matches("forecast")).isTrue();
        assertThat(ci.matches("forecast di terminal")).isFalse();

        FieldCheck cs = new FieldCheck("FORECAST", MatchMode.EQUALS, true);
        assertThat(cs.matches("forecast")).isFalse();
        assertThat(cs.matches("FORECAST")).isTrue();
    }

    @Test
    void containsMode() {
        assertThat(new FieldCheck("treno", MatchMode.CONTAINS, false).matches("AVVISI PARTENZA TRENO 4521")).isTrue();
        assertThat(new FieldCheck("treno", MatchMode.CONTAINS, true).matches("AVVISI PARTENZA TRENO 4521")).isFalse();
    }

    @Test
    @DisplayName("REGEX usa find(), non matches(): l'operatore scrive un pezzo, non l'intera stringa")
    void regexIsSubstringSemantics() {
        FieldCheck fc = new FieldCheck("AVVISI PARTENZA TRENO", MatchMode.REGEX, false);
        assertThat(fc.matches("AVVISI PARTENZA TRENO 4521 17/09/2026")).isTrue();

        FieldCheck anchored = new FieldCheck("^AVVISI PARTENZA TRENO \\d+$", MatchMode.REGEX, false);
        assertThat(anchored.matches("AVVISI PARTENZA TRENO 4521 17/09/2026")).isFalse();
        assertThat(anchored.matches("AVVISI PARTENZA TRENO 4521")).isTrue();
    }

    @Test
    @DisplayName("case folding con accenti (UNICODE_CASE)")
    void accentedCaseFolding() {
        assertThat(new FieldCheck("PARTENZA GIÀ", MatchMode.CONTAINS, false).matches("partenza già confermata")).isTrue();
        assertThat(new FieldCheck("già", MatchMode.REGEX, false).matches("GIÀ CONFERMATO")).isTrue();
    }

    @Test
    @DisplayName("backtracking catastrofico: budget esaurito -> eccezione, non poller appeso")
    void regexBudget() {
        // Misurato su JDK 17: senza budget questo pattern fa 201 milioni di accessi (536 ms) su 25
        // caratteri e 3,2 miliardi (8,4 s) su 29 - e cresce esponenzialmente. Molti "evil regex" da
        // manuale (a+)+$ non mordono piu' su JDK 17, questo si'.
        String evil = "(.*a){20}$";
        String input = "a".repeat(29) + "!";
        assertThatThrownBy(() -> new FieldCheck(evil, MatchMode.REGEX, false).matches(input, 100_000))
                .isInstanceOf(RegexUtil.RegexBudgetExceededException.class)
                .hasMessageContaining("regex troppo costosa");
    }

    @Test
    @DisplayName("il budget non scatta su pattern legittimi")
    void budgetNonInterferisce() {
        String subject = "AVVISI PARTENZA TRENO 4521 17/09/2026 ".repeat(13);   // ~480 caratteri
        FieldCheck fc = new FieldCheck("^AVVISI PARTENZA TRENO (\\d+) (\\d{2}/\\d{2}/\\d{4})", MatchMode.REGEX, false);
        assertThat(fc.matches(subject, 100_000)).isTrue();
    }

    @Test
    void regexNonValidaEUnErroreDiConfigurazione() {
        assertThatThrownBy(() -> new FieldCheck("[", MatchMode.REGEX, false).matches("x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regex non valida");
    }

    @Test
    void describe() {
        assertThat(FieldCheck.wildcard().describe()).isEqualTo("(qualsiasi)");
        assertThat(new FieldCheck("TRENO", MatchMode.REGEX, true).describe()).isEqualTo("REGEX \"TRENO\" [Aa]");
    }
}
