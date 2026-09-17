package it.gruppobernardini.switchmail.processor;

import java.util.List;

/**
 * Dichiarazione di un parametro per-regola. Da qui la UI genera il form e il server valida.
 *
 * <p>Il modello e' deliberatamente <b>piatto e chiuso</b>: niente oggetti annidati, niente array di
 * oggetti, niente visibilita' condizionale, niente validazione cross-field. E' la disciplina che
 * tiene economico il form auto-generato. Un processore che ha bisogno di struttura dichiara un
 * parametro {@link ParamType#JSON} e valida il blob da se'.
 */
public record ParamSpec(
        String key,
        String label,
        ParamType type,
        boolean required,
        String defaultValue,
        List<String> options,
        String help) {

    public enum ParamType {
        STRING, TEXT, INT, BOOL, SELECT, JSON
    }

    public ParamSpec {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("ParamSpec senza key");
        }
        options = options == null ? List.of() : List.copyOf(options);
        if (type == ParamType.SELECT && options.isEmpty()) {
            throw new IllegalArgumentException("ParamSpec SELECT senza options: " + key);
        }
    }

    public static ParamSpec string(String key, String label, boolean required, String def, String help) {
        return new ParamSpec(key, label, ParamType.STRING, required, def, null, help);
    }

    public static ParamSpec text(String key, String label, boolean required, String def, String help) {
        return new ParamSpec(key, label, ParamType.TEXT, required, def, null, help);
    }

    public static ParamSpec integer(String key, String label, boolean required, String def, String help) {
        return new ParamSpec(key, label, ParamType.INT, required, def, null, help);
    }

    public static ParamSpec bool(String key, String label, boolean required, String def, String help) {
        return new ParamSpec(key, label, ParamType.BOOL, required, def, null, help);
    }

    public static ParamSpec select(String key, String label, boolean required, String def,
                                   List<String> options, String help) {
        return new ParamSpec(key, label, ParamType.SELECT, required, def, options, help);
    }

    public static ParamSpec json(String key, String label, boolean required, String def, String help) {
        return new ParamSpec(key, label, ParamType.JSON, required, def, null, help);
    }
}
