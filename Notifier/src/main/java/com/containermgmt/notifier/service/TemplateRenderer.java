package com.containermgmt.notifier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service per il rendering di template con variabili estratte da eventi
 * Supporta conversione da JSON a mappa di variabili
 */
@Service
public class TemplateRenderer {

    private static final Logger logger = LoggerFactory.getLogger(TemplateRenderer.class);

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Converte un evento (Map) in una mappa di variabili flat per il template
     * Supporta nested fields con dot notation (es: supplier.name)
     *
     * @param eventData Dati evento da Valkey stream
     * @return Mappa di variabili flat (chiave -> valore string)
     */
    public Map<String, String> eventToVariables(Map<String, String> eventData) {
        Map<String, String> variables = new HashMap<>();

        if (eventData == null || eventData.isEmpty()) {
            return variables;
        }

        // Per ogni campo nell'evento, aggiungi come variabile
        for (Map.Entry<String, String> entry : eventData.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value != null) {
                // Prova a fare il parse come JSON per supportare oggetti nested
                try {
                    JsonNode node = objectMapper.readTree(value);
                    if (node.isObject()) {
                        // Se è un oggetto JSON, estrai i campi nested
                        flattenJson(key, node, variables);
                    } else {
                        // Se è un valore primitivo, aggiungilo direttamente
                        variables.put(key, value);
                    }
                } catch (Exception e) {
                    // Non è JSON, trattalo come stringa semplice
                    variables.put(key, value);
                }
            } else {
                variables.put(key, "");
            }
        }

        logger.debug("Converted event to {} variables", variables.size());
        return variables;
    }

    /**
     * Converte un evento (Map) in una mappa di variabili preservando la struttura
     * Gli array sono preservati come List<Object>, gli oggetti come Map<String, Object>
     * Questo metodo è usato con Handlebars per supportare foreach e template avanzati
     *
     * @param eventData Dati evento da Valkey stream
     * @return Mappa di variabili con struttura preservata (supporta array e oggetti nested)
     */
    public Map<String, Object> eventToContext(Map<String, String> eventData) {
        Map<String, Object> context = new HashMap<>();

        if (eventData == null || eventData.isEmpty()) {
            return context;
        }

        logger.info("###DEBUG### eventToContext - Input eventData keys: {}", eventData.keySet());

        // Per ogni campo nell'evento, prova a parsarlo come JSON
        for (Map.Entry<String, String> entry : eventData.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            logger.info("###DEBUG### Processing key: {}", key);
            logger.info("###DEBUG### Raw value: {}", value);
            logger.info("###DEBUG### Raw value class: {}", value != null ? value.getClass().getName() : "null");

            if (value != null) {
                // Prima verifica: se la stringa sembra JSON (ma non è escaped), prova il parsing diretto
                String trimmed = value.trim();
                boolean looksLikeJson = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                                       (trimmed.startsWith("[") && trimmed.endsWith("]"));

                if (looksLikeJson) {
                    try {
                        logger.info("###DEBUG### Value looks like JSON object/array, attempting direct parse");
                        JsonNode node = objectMapper.readTree(value);
                        Object parsedValue = parseJsonNode(node);
                        logger.info("###DEBUG### Successfully parsed JSON - type: {}", parsedValue.getClass().getName());
                        context.put(key, parsedValue);
                        continue; // Prossimo campo
                    } catch (Exception e) {
                        logger.warn("###DEBUG### Direct JSON parse failed for key '{}': {}", key, e.getMessage());

                        // Potrebbe essere JSON escaped (con \"), prova a togliere gli escape
                        if (trimmed.contains("\\\"")) {
                            try {
                                String unescaped = trimmed.replace("\\\"", "\"");
                                logger.info("###DEBUG### Trying with unescaped JSON: [{}]", unescaped);
                                JsonNode unescapedNode = objectMapper.readTree(unescaped);
                                Object unescapedValue = parseJsonNode(unescapedNode);
                                logger.info("###DEBUG### Successfully parsed unescaped JSON - type: {}", unescapedValue.getClass().getName());
                                context.put(key, unescapedValue);
                                continue; // Prossimo campo
                            } catch (Exception e2) {
                                logger.warn("###DEBUG### Unescaped JSON parse also failed for key '{}': {}", key, e2.getMessage());
                            }
                        }
                        // Continua con i tentativi successivi
                    }
                }

                try {
                    // Prova a fare il parse come JSON (gestisce anche stringhe JSON escaped)
                    logger.info("###DEBUG### Attempting JSON parse with ObjectMapper for key '{}'", key);
                    JsonNode node = objectMapper.readTree(value);
                    logger.info("###DEBUG### Parsed as JSON - node type: {}", node.getNodeType());

                    // Se il nodo è una stringa semplice e sembra JSON escaped, prova a ri-parsare
                    if (node.isTextual()) {
                        String textValue = node.asText();
                        String trimmedText = textValue.trim();

                        // Se la stringa sembra JSON (inizia con { o [), prova a parsarla di nuovo
                        if ((trimmedText.startsWith("{") && trimmedText.endsWith("}")) ||
                            (trimmedText.startsWith("[") && trimmedText.endsWith("]"))) {
                            try {
                                logger.info("###DEBUG### String looks like escaped JSON, attempting to re-parse");
                                JsonNode innerNode = objectMapper.readTree(textValue);
                                Object innerParsedValue = parseJsonNode(innerNode);
                                logger.info("###DEBUG### Successfully parsed escaped JSON - type: {}", innerParsedValue.getClass().getName());
                                context.put(key, innerParsedValue);
                            } catch (Exception innerEx) {
                                logger.warn("###DEBUG### Re-parse failed, using as string: {}", innerEx.getMessage());
                                context.put(key, textValue);
                            }
                        } else {
                            // È solo una stringa normale
                            context.put(key, textValue);
                        }
                    } else {
                        // Converti il nodo JSON preservando la struttura
                        Object parsedValue = parseJsonNode(node);
                        logger.info("###DEBUG### Parsed value type: {}", parsedValue.getClass().getName());
                        context.put(key, parsedValue);
                    }

                } catch (Exception e) {
                    // Parsing fallito, trattalo come stringa semplice
                    logger.warn("###DEBUG### All parse attempts failed for key '{}'. Treating as string. Error: {}", key, e.getMessage());
                    context.put(key, value);
                }
            } else {
                // Valore null -> stampa "null" come richiesto
                context.put(key, "null");
            }
        }

        logger.info("###DEBUG### Final context structure:");
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            logger.info("###DEBUG###   {} -> {} (type: {})",
                entry.getKey(),
                entry.getValue(),
                entry.getValue() != null ? entry.getValue().getClass().getName() : "null");
        }

        logger.debug("Converted event to context with {} variables", context.size());
        return context;
    }

    /**
     * Converte un JsonNode in Object preservando la struttura
     * - Array -> List<Object>
     * - Object -> Map<String, Object>
     * - Primitivi -> String/Number/Boolean
     * - Null -> "null"
     *
     * @param node Nodo JSON da convertire
     * @return Object con struttura preservata
     */
    private Object parseJsonNode(JsonNode node) {
        if (node.isNull()) {
            return "null";
        } else if (node.isArray()) {
            // Converti array in List
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(parseJsonNode(item));
            }
            return list;
        } else if (node.isObject()) {
            // Converti oggetto in Map
            Map<String, Object> map = new HashMap<>();
            node.fields().forEachRemaining(entry -> {
                map.put(entry.getKey(), parseJsonNode(entry.getValue()));
            });
            return map;
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isNumber()) {
            return node.asText(); // Ritorna come stringa per evitare problemi di formattazione
        } else {
            // Stringa o altro tipo primitivo
            return node.asText();
        }
    }

    /**
     * Sanitizza una stringa JSON gestendo caratteri speciali non escaped
     * Questo metodo cerca di "aggiustare" JSON che potrebbero contenere
     * newline, tab o altri caratteri speciali non escaped correttamente
     *
     * @param jsonString Stringa JSON potenzialmente malformata
     * @return Stringa JSON sanitizzata
     */
    private String sanitizeJsonString(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return jsonString;
        }

        // Se la stringa non sembra essere JSON (non inizia con { o [), ritornala così com'è
        String trimmed = jsonString.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[") && !trimmed.startsWith("\"")) {
            return jsonString;
        }

        try {
            // Usa ObjectMapper per re-serializzare: legge come stringa e la serializza correttamente
            // Questo trucco funziona perché readValue con String.class gestisce meglio gli escape
            String parsedString = objectMapper.readValue(jsonString, String.class);
            return objectMapper.writeValueAsString(parsedString);
        } catch (Exception e) {
            // Se fallisce, proviamo un approccio manuale
            logger.debug("ObjectMapper sanitization failed, trying manual approach: {}", e.getMessage());

            // Approccio manuale: escape dei caratteri speciali nelle stringhe JSON
            // Questo è un approccio semplificato che funziona per la maggior parte dei casi
            StringBuilder sanitized = new StringBuilder();
            boolean inString = false;
            boolean escaped = false;

            for (int i = 0; i < jsonString.length(); i++) {
                char c = jsonString.charAt(i);

                if (escaped) {
                    // Carattere già escaped, mantienilo
                    sanitized.append(c);
                    escaped = false;
                    continue;
                }

                if (c == '\\') {
                    escaped = true;
                    sanitized.append(c);
                    continue;
                }

                if (c == '"') {
                    inString = !inString;
                    sanitized.append(c);
                    continue;
                }

                // Se siamo dentro una stringa, escape dei caratteri speciali non escaped
                if (inString) {
                    switch (c) {
                        case '\n':
                            sanitized.append("\\n");
                            break;
                        case '\r':
                            sanitized.append("\\r");
                            break;
                        case '\t':
                            sanitized.append("\\t");
                            break;
                        case '\b':
                            sanitized.append("\\b");
                            break;
                        case '\f':
                            sanitized.append("\\f");
                            break;
                        default:
                            sanitized.append(c);
                    }
                } else {
                    sanitized.append(c);
                }
            }

            return sanitized.toString();
        }
    }

    /**
     * Appiattisce un oggetto JSON in una mappa flat con dot notation
     * Es: {"supplier": {"name": "ACME"}} -> {"supplier.name": "ACME"}
     *
     * @param prefix Prefisso per le chiavi
     * @param node Nodo JSON da appiattire
     * @param result Mappa risultato
     */
    private void flattenJson(String prefix, JsonNode node, Map<String, String> result) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                JsonNode value = entry.getValue();

                if (value.isObject()) {
                    // Ricorsione per oggetti nested
                    flattenJson(key, value, result);
                } else if (value.isArray()) {
                    // Per array, converti a stringa JSON
                    result.put(key, value.toString());
                } else if (value.isNull()) {
                    result.put(key, "");
                } else {
                    // Valore primitivo
                    result.put(key, value.asText());
                }
            });
        } else if (node.isArray()) {
            // Array al livello root, converti a JSON string
            result.put(prefix, node.toString());
        } else if (node.isNull()) {
            result.put(prefix, "");
        } else {
            // Valore primitivo
            result.put(prefix, node.asText());
        }
    }

    /**
     * Converte un JSON string in mappa di variabili
     *
     * @param jsonString JSON string dell'evento
     * @return Mappa di variabili flat
     */
    public Map<String, String> jsonToVariables(String jsonString) {
        Map<String, String> variables = new HashMap<>();

        if (jsonString == null || jsonString.trim().isEmpty()) {
            return variables;
        }

        try {
            JsonNode root = objectMapper.readTree(jsonString);
            flattenJson("", root, variables);
            logger.debug("Parsed JSON to {} variables", variables.size());
        } catch (Exception e) {
            logger.error("Error parsing JSON string: {}", e.getMessage(), e);
        }

        return variables;
    }

    /**
     * Merge di più mappe di variabili
     * Utile per combinare variabili da diverse fonti
     *
     * @param maps Mappe da unire
     * @return Mappa unificata (le ultime sovrascrivono le prime in caso di conflitto)
     */
    @SafeVarargs
    public final Map<String, String> mergeVariables(Map<String, String>... maps) {
        Map<String, String> result = new HashMap<>();

        for (Map<String, String> map : maps) {
            if (map != null) {
                result.putAll(map);
            }
        }

        return result;
    }

}
