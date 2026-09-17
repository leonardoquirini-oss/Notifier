package it.gruppobernardini.switchmail.model;

import java.util.List;

/**
 * Cosa succede a una mail: le regole da eseguire (in ordine) e la traccia completa della
 * valutazione, che e' quello che /ruletest mostra.
 */
public record MatchedRules(List<RuleConfig> toExecute, List<RuleMatchResult> trace) {

    public MatchedRules {
        toExecute = toExecute == null ? List.of() : List.copyOf(toExecute);
        trace = trace == null ? List.of() : List.copyOf(trace);
    }

    public boolean isEmpty() {
        return toExecute.isEmpty();
    }

    public RuleConfig first() {
        return toExecute.isEmpty() ? null : toExecute.get(0);
    }

    public List<Long> ids() {
        return toExecute.stream().map(RuleConfig::id).toList();
    }
}
