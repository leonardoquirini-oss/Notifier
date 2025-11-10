package com.containermgmt.notifier.config;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Configurazione Handlebars per il rendering dei template email
 * Fornisce helper custom per funzionalità avanzate come formattazione date
 */
@Configuration
public class HandlebarsConfig {

    private static final Logger logger = LoggerFactory.getLogger(HandlebarsConfig.class);

    /**
     * Crea e configura l'istanza Handlebars con helper custom
     *
     * @return Handlebars configurato
     */
    @Bean
    public Handlebars handlebars() {
        Handlebars handlebars = new Handlebars();

        // Registra helper custom per tag {{now "formato"}} o {{now formato}}
        handlebars.registerHelper("now", new Helper<Object>() {
            @Override
            public CharSequence apply(Object context, Options options) throws IOException {
                logger.info("###DEBUG### Helper 'now' chiamato");
                logger.info("###DEBUG### Context: {}", context);
                logger.info("###DEBUG### Numero parametri: {}", options.params.length);

                for (int i = 0; i < options.params.length; i++) {
                    logger.info("###DEBUG### Parametro {}: {}", i, options.params[i]);
                }

                // Il formato può essere passato in due modi:
                // 1. Come context: {{now DD/MM/YYYY}} -> context contiene "DD/MM/YYYY"
                // 2. Come parametro: {{now "DD/MM/YYYY"}} -> params[0] contiene "DD/MM/YYYY"
                String format;

                if (options.params.length > 0) {
                    // Formato passato come parametro con virgolette
                    format = options.param(0, "dd/MM/yyyy HH:mm:ss");
                } else if (context != null && context instanceof String) {
                    // Formato passato come context senza virgolette
                    format = (String) context;
                } else {
                    // Default
                    format = "dd/MM/yyyy HH:mm:ss";
                }

                logger.info("###DEBUG### Formato rilevato: {}", format);

                try {
                    // Converti formato da maiuscolo a pattern Java se necessario
                    String javaPattern = convertToJavaPattern(format);
                    logger.info("###DEBUG### Pattern Java: {}", javaPattern);

                    // Formatta la data corrente
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(javaPattern);
                    String result = LocalDateTime.now().format(formatter);
                    logger.info("###DEBUG### Risultato formattazione: {}", result);

                    return result;

                } catch (Exception e) {
                    // Se il formato non è valido, ritorna errore
                    logger.error("###DEBUG### Errore formattazione: {}", e.getMessage(), e);
                    return "[ERROR: Invalid date format '" + format + "']";
                }
            }
        });

        // Registra helper custom per tag {{now "formato"}} o {{now formato}}
        handlebars.registerHelper("isTruthy", new Helper<Object>() {
            @Override
            public Object apply(Object context, Options options) throws IOException {
                if (context == null) return options.inverse();

                String str = context.toString().trim();

                // Considera "falsy": null, "", "null", "0", "false"
                if (str.isEmpty() || "null".equalsIgnoreCase(str) || "false".equalsIgnoreCase(str) || "0".equals(str)) {
                    return options.inverse();
                }

                // Altrimenti è truthy
                return options.fn();        
            }
        });

        // Registra helper custom per tag {{eq}} 
        handlebars.registerHelper("eq", new Helper<Object>() {
            @Override
            public Object apply(Object context, Options options) throws IOException {
                Object left = context;
                Object right = options.param(0, null);
                if (left == null || right == null) {
                    return options.inverse();
                }
                if (left.toString().equals(right.toString())) {
                    return options.fn(context);   // ramo TRUE
                } else {
                    return options.inverse();     // ramo ELSE
                }    
            }
        });

        // Configura opzioni Handlebars
        handlebars.setPrettyPrint(false);  // Non formattare l'output (mantieni HTML come da template)
        handlebars.setInfiniteLoops(false);  // Previeni loop infiniti

        return handlebars;
    }

    /**
     * Converte formato data da maiuscolo a pattern Java DateTimeFormatter
     *
     * Conversioni supportate:
     * - YYYY → yyyy (anno 4 cifre)
     * - YY → yy (anno 2 cifre)
     * - DD → dd (giorno del mese)
     * - MM → MM (mese - rimane invariato)
     * - HH → HH (ora 24h - rimane invariato)
     * - mm → mm (minuti - rimane invariato)
     * - ss → ss (secondi - rimane invariato)
     *
     * @param customFormat Formato con lettere maiuscole (es: "DD/MM/YYYY HH:mm:ss")
     * @return Pattern Java per DateTimeFormatter (es: "dd/MM/yyyy HH:mm:ss")
     */
    private String convertToJavaPattern(String customFormat) {
        if (customFormat == null) return "dd/MM/yyyy HH:mm:ss";

        String result = customFormat;

        // IMPORTANTE: Sostituire YYYY prima di YY per evitare sostituzioni parziali
        result = result.replace("YYYY", "yyyy");  // Anno 4 cifre
        result = result.replace("YY", "yy");      // Anno 2 cifre
        result = result.replace("DD", "dd");      // Giorno del mese

        // MM, HH, mm, ss rimangono invariati (già nel formato Java corretto)

        return result;
    }
}
