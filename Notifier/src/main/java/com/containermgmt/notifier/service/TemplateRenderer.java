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

            if (value != null) {
                try {
                    // Prova a fare il parse come JSON
                    JsonNode node = objectMapper.readTree(value);
                    logger.info("###DEBUG### Parsed as JSON - node type: {}", node.getNodeType());

                    // Converti il nodo JSON preservando la struttura
                    Object parsedValue = parseJsonNode(node);
                    logger.info("###DEBUG### Parsed value type: {}", parsedValue.getClass().getName());
                    logger.info("###DEBUG### Parsed value: {}", parsedValue);

                    context.put(key, parsedValue);

                } catch (Exception e) {
                    // Non è JSON, trattalo come stringa semplice
                    logger.info("###DEBUG### Not JSON, treating as string: {}", e.getMessage());
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
