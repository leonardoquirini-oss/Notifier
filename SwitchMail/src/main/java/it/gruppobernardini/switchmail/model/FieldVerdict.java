package it.gruppobernardini.switchmail.model;

/**
 * Il verdetto su un singolo campo, con il valore che e' stato confrontato.
 *
 * <p>Il "perche' no" e' tutto il punto di /ruletest: senza il valore effettivo, una regola che non
 * matcha e' indistinguibile da una regola scritta male.
 */
public record FieldVerdict(String field, String check, boolean matched, String value, String note) {

    public static FieldVerdict ok(String field, String check, String value, String note) {
        return new FieldVerdict(field, check, true, value, note);
    }

    public static FieldVerdict ko(String field, String check, String value, String note) {
        return new FieldVerdict(field, check, false, value, note);
    }
}
