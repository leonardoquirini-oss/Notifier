package it.gruppobernardini.switchmail.model;

import it.gruppobernardini.switchmail.util.RegexUtil;

/**
 * Un controllo su un campo della mail: pattern + modalita' + case sensitivity.
 * Funzione pura, senza Spring e senza DB: e' il pezzo piu' testabile del matching.
 *
 * <p>Un pattern vuoto o null e' un <b>wildcard</b>: il campo non vincola nulla. Che tutti e tre i
 * campi di una regola siano wildcard e' impedito da un CHECK sullo schema, non dal codice.
 */
public record FieldCheck(String pattern, MatchMode mode, boolean caseSensitive) {

    public enum MatchMode {
        EQUALS, CONTAINS, REGEX
    }

    public FieldCheck {
        if (mode == null) {
            mode = MatchMode.CONTAINS;
        }
    }

    public static FieldCheck wildcard() {
        return new FieldCheck(null, MatchMode.CONTAINS, false);
    }

    public boolean isWildcard() {
        return pattern == null || pattern.isBlank();
    }

    public boolean matches(String value) {
        return matches(value, RegexUtil.DEFAULT_MAX_STEPS);
    }

    /**
     * Un valore null non matcha mai (tranne wildcard): "oggetto assente" non e' "oggetto qualsiasi".
     *
     * @throws RegexUtil.RegexBudgetExceededException se una REGEX esaurisce il budget di passi;
     *         il chiamante la tratta come "non matcha" e logga un warning.
     */
    public boolean matches(String value, int regexMaxSteps) {
        if (isWildcard()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return switch (mode) {
            case EQUALS -> caseSensitive ? pattern.equals(value) : pattern.equalsIgnoreCase(value);
            case CONTAINS -> caseSensitive
                    ? value.contains(pattern)
                    : value.toLowerCase().contains(pattern.toLowerCase());
            case REGEX -> RegexUtil.find(pattern, value, caseSensitive, regexMaxSteps);
        };
    }

    /** Descrizione leggibile per la traccia di /ruletest e per i chip della lista regole. */
    public String describe() {
        return isWildcard() ? "(qualsiasi)" : mode + " \"" + pattern + "\"" + (caseSensitive ? " [Aa]" : "");
    }
}
