package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.model.FieldCheck;
import it.gruppobernardini.switchmail.model.FieldVerdict;
import it.gruppobernardini.switchmail.model.MatchedRules;
import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.RuleConfig;
import it.gruppobernardini.switchmail.model.RuleMatchResult;
import it.gruppobernardini.switchmail.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Quale regola prende la mail. Funzione pura: niente DB, niente IMAP, niente stato.
 *
 * <p>Costruibile a mano nei test con {@code new RuleMatcher(new SwitchMailProperties())}.
 */
@Component
@Slf4j
public class RuleMatcher {

    private final int regexMaxSteps;
    private final int subjectMaxChars;

    public RuleMatcher(SwitchMailProperties props) {
        this.regexMaxSteps = props.getMatcher().getRegexMaxSteps();
        this.subjectMaxChars = props.getMatcher().getSubjectMaxChars();
    }

    /**
     * Valuta tutte le regole nell'ordine dato e ritorna traccia completa + regole da eseguire.
     *
     * <p>Con {@code stop_on_match = 1} (default) vince la prima: la valutazione si ferma e le regole
     * successive che avrebbero matchato restano nella traccia marcate come oscurate - che e' dove
     * l'informazione serve davvero.
     *
     * <p>Con {@code stop_on_match = 0} la valutazione prosegue e tutte le regole che matchano
     * verranno eseguite nello stesso tentativo, in ordine di priorita'.
     */
    public MatchedRules evaluate(ParsedMail mail, List<RuleConfig> rules) {
        List<RuleMatchResult> trace = new ArrayList<>();
        List<RuleConfig> toExecute = new ArrayList<>();
        Long stoppedBy = null;

        for (RuleConfig rule : rules) {
            if (!rule.enabled()) {
                continue;
            }
            Verdict verdict = check(mail, rule);

            if (stoppedBy != null) {
                trace.add(new RuleMatchResult(rule, verdict.matched, verdict.verdicts,
                        verdict.matched ? stoppedBy : null, verdict.warning));
                continue;
            }

            trace.add(new RuleMatchResult(rule, verdict.matched, verdict.verdicts, null, verdict.warning));
            if (verdict.matched) {
                toExecute.add(rule);
                if (rule.stopOnMatch()) {
                    stoppedBy = rule.id();
                }
            }
        }
        return new MatchedRules(toExecute, trace);
    }

    /** Hot path del poll: stessa logica, senza costruire la traccia per le regole non rilevanti. */
    public MatchedRules selectFor(ParsedMail mail, List<RuleConfig> rules) {
        return evaluate(mail, rules);
    }

    private record Verdict(boolean matched, List<FieldVerdict> verdicts, String warning) {
    }

    private Verdict check(ParsedMail mail, RuleConfig rule) {
        List<FieldVerdict> verdicts = new ArrayList<>();
        String warning = null;
        boolean matched = true;

        // --- mittente: vale sull'indirizzo nudo OPPURE sul From completo -------------------------
        // Altrimenti la prima regola di ogni operatore fallisce per un motivo invisibile: scrive
        // "Mario Rossi" e il match gira solo su mario.rossi@ferrovie.it (o viceversa).
        if (!rule.sender().isWildcard()) {
            String address = mail.fromAddress();
            String full = mail.fromFull();
            try {
                boolean onAddress = rule.sender().matches(address, regexMaxSteps);
                boolean onFull = !onAddress && rule.sender().matches(full, regexMaxSteps);
                if (onAddress || onFull) {
                    verdicts.add(FieldVerdict.ok("mittente", rule.sender().describe(),
                            onAddress ? address : full, onAddress ? "match sull'indirizzo" : "match sul From completo"));
                } else {
                    matched = false;
                    verdicts.add(FieldVerdict.ko("mittente", rule.sender().describe(), full,
                            "ne' l'indirizzo ne' il From completo corrispondono"));
                }
            } catch (RegexUtil.RegexBudgetExceededException e) {
                matched = false;
                warning = "mittente: " + e.getMessage();
                verdicts.add(FieldVerdict.ko("mittente", rule.sender().describe(), full, e.getMessage()));
                log.warn("Regola {}: budget regex esaurito sul mittente", rule.label());
            }
        }

        // --- oggetto (troncato: input lungo = costo del match) -----------------------------------
        if (!rule.subject().isWildcard()) {
            String subject = truncate(mail.subject());
            try {
                if (rule.subject().matches(subject, regexMaxSteps)) {
                    verdicts.add(FieldVerdict.ok("oggetto", rule.subject().describe(), subject, null));
                } else {
                    matched = false;
                    verdicts.add(FieldVerdict.ko("oggetto", rule.subject().describe(), subject, null));
                }
            } catch (RegexUtil.RegexBudgetExceededException e) {
                matched = false;
                warning = "oggetto: " + e.getMessage();
                verdicts.add(FieldVerdict.ko("oggetto", rule.subject().describe(), subject, e.getMessage()));
                log.warn("Regola {}: budget regex esaurito sull'oggetto", rule.label());
            }
        }

        // --- nome allegato: basta che UNO corrisponda --------------------------------------------
        if (!rule.attachment().isWildcard()) {
            List<String> names = mail.attachmentNames();
            try {
                String hit = names.stream()
                        .filter(n -> rule.attachment().matches(n, regexMaxSteps))
                        .findFirst().orElse(null);
                if (hit != null) {
                    verdicts.add(FieldVerdict.ok("allegato", rule.attachment().describe(), hit, null));
                } else {
                    matched = false;
                    verdicts.add(FieldVerdict.ko("allegato", rule.attachment().describe(), String.valueOf(names),
                            names.isEmpty() ? "la mail non ha allegati" : null));
                }
            } catch (RegexUtil.RegexBudgetExceededException e) {
                matched = false;
                warning = "allegato: " + e.getMessage();
                verdicts.add(FieldVerdict.ko("allegato", rule.attachment().describe(), String.valueOf(names),
                        e.getMessage()));
                log.warn("Regola {}: budget regex esaurito sul nome allegato", rule.label());
            }
        }

        // --- richiesta esplicita di allegato ------------------------------------------------------
        if (rule.requireAttachment()) {
            if (mail.hasAttachments()) {
                verdicts.add(FieldVerdict.ok("richiede allegato", "si'", String.valueOf(mail.attachmentNames()), null));
            } else {
                matched = false;
                verdicts.add(FieldVerdict.ko("richiede allegato", "si'", "[]", "la mail non ha allegati"));
            }
        }

        return new Verdict(matched, verdicts, warning);
    }

    private String truncate(String subject) {
        if (subject == null) {
            return null;
        }
        return subject.length() <= subjectMaxChars ? subject : subject.substring(0, subjectMaxChars);
    }
}
