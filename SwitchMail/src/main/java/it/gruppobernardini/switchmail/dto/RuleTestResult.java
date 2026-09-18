package it.gruppobernardini.switchmail.dto;

import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.RuleConfig;
import it.gruppobernardini.switchmail.model.RuleMatchResult;

import java.util.List;
import java.util.Map;

/**
 * Output di /ruletest: cosa ha visto l'extractor, come ha votato ogni regola e (se richiesto) cosa
 * sarebbe successo eseguendo.
 */
public record RuleTestResult(
        ParsedMail mail,
        List<RuleMatchResult> trace,
        RuleConfig winner,
        Map<String, Object> effectiveParams,
        String paramsError,
        DryRunResult dryRun) {

    public RuleTestResult {
        trace = trace == null ? List.of() : List.copyOf(trace);
    }
}
