package it.gruppobernardini.switchmail;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.model.FieldCheck;
import it.gruppobernardini.switchmail.model.FieldCheck.MatchMode;
import it.gruppobernardini.switchmail.model.MatchedRules;
import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.RuleConfig;
import it.gruppobernardini.switchmail.model.RuleMatchResult;
import it.gruppobernardini.switchmail.service.RuleMatcher;
import it.gruppobernardini.switchmail.support.MailFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleMatcherTest {

    private final SwitchMailProperties props = new SwitchMailProperties();
    private final RuleMatcher matcher = new RuleMatcher(props);

    private static RuleConfig rule(long id, String name, int priority, FieldCheck subject) {
        return MailFixtures.rule(id, name, priority, "p" + id, FieldCheck.wildcard(), subject, FieldCheck.wildcard());
    }

    private static RuleConfig withStop(RuleConfig r, boolean stopOnMatch) {
        return new RuleConfig(r.id(), r.name(), r.description(), r.accountId(), r.enabled(), r.priority(),
                stopOnMatch, r.sender(), r.subject(), r.attachment(), r.requireAttachment(), r.processorId(),
                r.paramsJson(), r.maxAttempts(), r.createdAt(), r.updatedAt());
    }

    @Test
    @DisplayName("vince la prima per priorita'; le successive restano in traccia come oscurate")
    void primaVinceEOscuraLeAltre() {
        ParsedMail mail = MailFixtures.mail("a@ferrovie.it", "AVVISI PARTENZA TRENO 4521", "");
        List<RuleConfig> rules = List.of(
                rule(1, "specifica", 10, new FieldCheck("PARTENZA TRENO", MatchMode.CONTAINS, false)),
                rule(2, "generica", 100, new FieldCheck("AVVISI", MatchMode.CONTAINS, false)));

        MatchedRules matched = matcher.evaluate(mail, rules);

        assertThat(matched.ids()).containsExactly(1L);
        RuleMatchResult oscurata = matched.trace().stream().filter(t -> t.rule().id() == 2L).findFirst().orElseThrow();
        assertThat(oscurata.matched()).isTrue();
        assertThat(oscurata.shadowedBy()).isEqualTo(1L);
        assertThat(oscurata.executed()).isFalse();
    }

    @Test
    @DisplayName("stop_on_match = 0: tutte le regole che matchano vengono eseguite, in ordine")
    void stopOnMatchDisattivato() {
        ParsedMail mail = MailFixtures.mail("a@ferrovie.it", "AVVISI PARTENZA TRENO 4521", "");
        List<RuleConfig> rules = List.of(
                withStop(rule(1, "prima", 10, new FieldCheck("AVVISI", MatchMode.CONTAINS, false)), false),
                rule(2, "seconda", 20, new FieldCheck("TRENO", MatchMode.CONTAINS, false)));

        MatchedRules matched = matcher.evaluate(mail, rules);

        assertThat(matched.ids()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("le regole disabilitate non entrano nemmeno in traccia")
    void regoleDisabilitate() {
        ParsedMail mail = MailFixtures.mail("a@ferrovie.it", "AVVISI", "");
        RuleConfig attiva = rule(1, "attiva", 10, new FieldCheck("AVVISI", MatchMode.CONTAINS, false));
        RuleConfig spenta = new RuleConfig(2L, "spenta", null, null, false, 5, true, FieldCheck.wildcard(),
                new FieldCheck("AVVISI", MatchMode.CONTAINS, false), FieldCheck.wildcard(), false, "p2", "{}", 3,
                MailFixtures.T0, MailFixtures.T0);

        MatchedRules matched = matcher.evaluate(mail, List.of(spenta, attiva));

        assertThat(matched.ids()).containsExactly(1L);
        assertThat(matched.trace()).hasSize(1);
    }

    @Test
    @DisplayName("il mittente matcha sull'indirizzo nudo E sul From completo, e la traccia dice quale")
    void mittenteIndirizzoOFromCompleto() {
        ParsedMail mail = MailFixtures.mail("mario.rossi@ferrovie.it", "Mario Rossi", "Oggetto", "corpo",
                7L, List.of(), List.of());

        RuleConfig suIndirizzo = MailFixtures.rule(1, "indirizzo", 10, "p1",
                new FieldCheck("mario.rossi@ferrovie.it", MatchMode.EQUALS, false),
                FieldCheck.wildcard(), FieldCheck.wildcard());
        RuleConfig suNome = MailFixtures.rule(2, "nome", 10, "p2",
                new FieldCheck("Mario Rossi", MatchMode.CONTAINS, false),
                FieldCheck.wildcard(), FieldCheck.wildcard());

        assertThat(matcher.evaluate(mail, List.of(suIndirizzo)).ids()).containsExactly(1L);
        MatchedRules perNome = matcher.evaluate(mail, List.of(suNome));
        assertThat(perNome.ids()).containsExactly(2L);
        assertThat(perNome.trace().get(0).verdicts())
                .anySatisfy(v -> assertThat(v.note()).contains("From completo"));
    }

    @Test
    @DisplayName("require_attachment: senza allegati la regola non matcha e il motivo e' scritto")
    void richiedeAllegato() {
        ParsedMail senza = MailFixtures.mail("a@ferrovie.it", "AVVISI", "");
        RuleConfig r = new RuleConfig(1L, "con allegato", null, null, true, 10, true, FieldCheck.wildcard(),
                new FieldCheck("AVVISI", MatchMode.CONTAINS, false), FieldCheck.wildcard(), true, "p1", "{}", 3,
                MailFixtures.T0, MailFixtures.T0);

        MatchedRules matched = matcher.evaluate(senza, List.of(r));

        assertThat(matched.isEmpty()).isTrue();
        assertThat(matched.trace().get(0).verdicts())
                .anySatisfy(v -> assertThat(v.note()).contains("non ha allegati"));
    }

    @Test
    @DisplayName("il nome allegato matcha se almeno un allegato corrisponde")
    void nomeAllegato() {
        ParsedMail mail = MailFixtures.mail("a@ferrovie.it", null, "AVVISI", "", 7L,
                List.of(MailFixtures.txt("listato.pdf", "x"), MailFixtures.txt("treno.txt", "y")), List.of());
        RuleConfig r = MailFixtures.rule(1, "txt", 10, "p1", FieldCheck.wildcard(), FieldCheck.wildcard(),
                new FieldCheck("(?i).*\\.txt$", MatchMode.REGEX, false));

        assertThat(matcher.evaluate(mail, List.of(r)).ids()).containsExactly(1L);
    }

    @Test
    @DisplayName("budget regex esaurito: la regola non matcha e lascia un warning, il poll non si appende")
    void budgetRegexEsaurito() {
        props.getMatcher().setRegexMaxSteps(100_000);
        RuleMatcher m = new RuleMatcher(props);
        ParsedMail mail = MailFixtures.mail("a@ferrovie.it", "a".repeat(29) + "!", "");
        RuleConfig cattiva = rule(1, "cattiva", 10, new FieldCheck("(.*a){20}$", MatchMode.REGEX, false));

        MatchedRules matched = m.evaluate(mail, List.of(cattiva));

        assertThat(matched.isEmpty()).isTrue();
        assertThat(matched.trace().get(0).warning()).contains("regex troppo costosa");
    }

    @Test
    @DisplayName("oggetto troncato prima del match: input lungo = costo del match")
    void oggettoTroncato() {
        props.getMatcher().setSubjectMaxChars(10);
        RuleMatcher m = new RuleMatcher(props);
        ParsedMail mail = MailFixtures.mail("a@ferrovie.it", "0123456789TROVAMI", "");

        assertThat(m.evaluate(mail, List.of(rule(1, "r", 10,
                new FieldCheck("TROVAMI", MatchMode.CONTAINS, false)))).isEmpty()).isTrue();
        assertThat(m.evaluate(mail, List.of(rule(2, "r", 10,
                new FieldCheck("012345", MatchMode.CONTAINS, false)))).ids()).containsExactly(2L);
    }
}
