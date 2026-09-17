package it.gruppobernardini.switchmail.processor;

import it.gruppobernardini.switchmail.util.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * I parametri di una regola, gia' validati e coercizzati contro le {@link ParamSpec} del processore.
 *
 * <p>Questa e' la meta' costosa del meccanismo dei parametri, e serve comunque lato server (la API
 * si puo' chiamare anche senza passare dal form). Una volta che esiste, il generatore di form nella
 * UI e' una quarantina di righe di JS.
 *
 * <p>{@link #of} <b>rifiuta le chiavi sconosciute</b>: un parametro che il processore non legge e'
 * una bugia nella UI, e intercettarlo al salvataggio della regola e' la differenza tra "la config
 * e' sbagliata" e "il codice e' sbagliato".
 */
public final class ProcessorParams {

    private final Map<String, Object> values;

    private ProcessorParams(Map<String, Object> values) {
        this.values = values;
    }

    public static ProcessorParams empty() {
        return new ProcessorParams(Map.of());
    }

    public static ProcessorParams ofJson(String paramsJson, List<ParamSpec> specs) {
        return of(JsonUtil.readMap(paramsJson), specs);
    }

    /**
     * @throws IllegalArgumentException con un messaggio mostrabile in UI: il controller lo mappa a 400.
     */
    public static ProcessorParams of(Map<String, Object> raw, List<ParamSpec> specs) {
        Map<String, Object> input = raw == null ? Map.of() : raw;
        List<ParamSpec> specList = specs == null ? List.of() : specs;

        Map<String, ParamSpec> byKey = new LinkedHashMap<>();
        for (ParamSpec s : specList) {
            byKey.put(s.key(), s);
        }

        for (String key : input.keySet()) {
            if (!byKey.containsKey(key)) {
                throw new IllegalArgumentException("parametro sconosciuto: '" + key + "'"
                        + (byKey.isEmpty() ? " (questo processore non ha parametri)"
                                           : " (previsti: " + String.join(", ", byKey.keySet()) + ")"));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (ParamSpec spec : specList) {
            Object rawValue = input.get(spec.key());
            if (isAbsent(rawValue)) {
                rawValue = spec.defaultValue();
            }
            if (isAbsent(rawValue)) {
                if (spec.required()) {
                    throw new IllegalArgumentException("parametro obbligatorio mancante: '" + spec.key() + "'"
                            + (spec.label() == null ? "" : " (" + spec.label() + ")"));
                }
                continue;
            }
            out.put(spec.key(), coerce(spec, rawValue));
        }
        return new ProcessorParams(out);
    }

    private static boolean isAbsent(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    private static Object coerce(ParamSpec spec, Object value) {
        String asString = String.valueOf(value).trim();
        return switch (spec.type()) {
            case STRING, TEXT -> asString;
            case INT -> {
                if (value instanceof Number n) {
                    yield n.intValue();
                }
                try {
                    yield Integer.parseInt(asString);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "parametro '" + spec.key() + "': atteso un intero, ricevuto \"" + asString + "\"");
                }
            }
            case BOOL -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                if ("true".equalsIgnoreCase(asString) || "1".equals(asString) || "on".equalsIgnoreCase(asString)) {
                    yield Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(asString) || "0".equals(asString) || "off".equalsIgnoreCase(asString)) {
                    yield Boolean.FALSE;
                }
                throw new IllegalArgumentException(
                        "parametro '" + spec.key() + "': atteso un booleano, ricevuto \"" + asString + "\"");
            }
            case SELECT -> {
                if (!spec.options().contains(asString)) {
                    throw new IllegalArgumentException("parametro '" + spec.key() + "': valore \"" + asString
                            + "\" non ammesso (valori: " + String.join(", ", spec.options()) + ")");
                }
                yield asString;
            }
            case JSON -> {
                String json = value instanceof String s ? s : JsonUtil.write(value);
                JsonUtil.requireValidJson(json, "parametro '" + spec.key() + "'");
                yield json;
            }
        };
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public String requireString(String key) {
        Object v = values.get(key);
        if (v == null) {
            throw new IllegalArgumentException("parametro '" + key + "' non impostato");
        }
        return String.valueOf(v);
    }

    public String getString(String key, String def) {
        Object v = values.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public int requireInt(String key) {
        Object v = values.get(key);
        if (v == null) {
            throw new IllegalArgumentException("parametro '" + key + "' non impostato");
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }

    public int getInt(String key, int def) {
        Object v = values.get(key);
        if (v == null) {
            return def;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }

    public boolean getBool(String key, boolean def) {
        Object v = values.get(key);
        if (v == null) {
            return def;
        }
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    /** I valori effettivi, default gia' applicati: e' quello che /ruletest mostra nella card vincitrice. */
    public Map<String, Object> asMap() {
        return Map.copyOf(values);
    }

    @Override
    public String toString() {
        return "ProcessorParams" + values;
    }
}
