package it.gruppobernardini.switchmail.model;

import java.util.List;

/**
 * Esito della valutazione di UNA regola su una mail.
 *
 * @param shadowedBy id della regola che ha vinto prima e ha fermato la valutazione (stop_on_match);
 *                   la regola qui avrebbe matchato ma non e' stata eseguita
 * @param warning    es. budget regex esaurito: la regola non matcha, ma non per "colpa" della mail
 */
public record RuleMatchResult(
        RuleConfig rule,
        boolean matched,
        List<FieldVerdict> verdicts,
        Long shadowedBy,
        String warning) {

    public RuleMatchResult {
        verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
    }

    public boolean executed() {
        return matched && shadowedBy == null;
    }
}
