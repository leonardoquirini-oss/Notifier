package com.containermgmt.notifier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
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
