package com.containermgmt.notifier.service;

import com.containermgmt.notifier.model.EmailList;
import com.containermgmt.notifier.model.EmailSendLog;
import com.containermgmt.notifier.model.EmailTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service per l'invio di email basate su template
 * Gestisce rendering variabili, invio SMTP e logging
 */
@Service
@Slf4j
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private Handlebars handlebars;

    @Value("${email.from.address}")
    private String fromAddress;

    @Value("${email.from.name}")
    private String fromName;

    @Value("${email.footer:}")
    private String emailFooter;

    @Value("${backend.api.url}")
    private String backendApiUrl;

    @Value("${backend.api.api-key}")
    private String backendApiKey;

    @Value("${backend.api.attachments.download-endpoint}")
    private String attachmentDownloadEndpoint;

    /**
     * Classe interna per rappresentare un allegato email
     */
    public static class EmailAttachment {
        private final byte[] data;
        private final String filename;
        private final String contentType;

        public EmailAttachment(byte[] data, String filename, String contentType) {
            this.data = data;
            this.filename = filename;
            this.contentType = contentType;
        }

        public byte[] getData() { return data; }
        public String getFilename() { return filename; }
        public String getContentType() { return contentType; }
    }

    /**
     * Invia email da template usando un HashMap di variabili
     *
     * @param templateId ID del template
     * @param variables Mappa variabili (supporta array per foreach e oggetti nested)
     * @param sentBy Username utente che invia
     * @return ID del log creato
     * @throws Exception se errore durante invio
     */
    public Integer sendFromTemplate(
            Integer templateId,
            Map<String, Object> variables,
            String sentBy) throws Exception {

        return sendFromTemplate(templateId, variables, null, null, sentBy);
    }

    public Integer sendFromTemplate(
            Integer templateId,
            Map<String, Object> variables,
            String entityType,
            Integer entityId,
            String sentBy) throws Exception {
                return sendFromTemplate(templateId, false, false, variables, entityType, entityId, sentBy);
            }


    /**
     * Invia email da template con collegamento a entità
     *
     * @param templateId ID del template
     * @param variables Mappa variabili per sostituzione (supporta array per foreach e oggetti nested)
     * @param entityType Tipo entità (opzionale, es: "operation")
     * @param entityId ID entità (opzionale)
     * @param sentBy Username utente che invia
     * @return ID del log creato
     * @throws Exception se errore durante invio
     */
    public Integer sendFromTemplate(
            Integer templateId,
            Boolean singleMail,
            Boolean emailListSpecified,
            Map<String, Object> variables,
            String entityType,
            Integer entityId,
            String sentBy) throws Exception {

        logger.info("Invio email da template ID={} per utente {} ", templateId, sentBy);

        // Carica template
        EmailTemplate template = EmailTemplate.findById(templateId);
        if (template == null || !template.isUsable()) {
            throw new IllegalArgumentException("Template non trovato o non utilizzabile: " + templateId);
        }

        // Ottiene liste associate al template
        List<EmailList> lists = template.getAssociatedLists();
        if ( lists.isEmpty() && !singleMail && !emailListSpecified) {
            throw new IllegalStateException("Nessuna lista email associata al template: " + templateId);
        }        

        // Rendering subject e body con variabili
        String renderedSubject = renderTemplate(template.getString("subject"), variables);
        String renderedBody = renderTemplate(template.getString("body"), variables);

        // Raccoglie tutti i destinatari da tutte le liste
        Set<String> toAddresses = new LinkedHashSet<>();
        Set<String> ccAddresses = new LinkedHashSet<>();
        Set<String> bccAddresses = new LinkedHashSet<>();

        if ( singleMail ) {
            Map<String, Object> parameters = (Map<String, Object>)(variables.getOrDefault("parameters", null));
            if ( parameters != null ) {
                String email = (String) parameters.getOrDefault("email_list", "");
                log.info("Sending mail to {}", email);
                toAddresses.add(email);
            }
        } else if ( emailListSpecified ) {
            Map<String, Object> parameters = (Map<String, Object>)(variables.getOrDefault("parameters", null));
            if ( parameters != null ) {
                String emailList = (String) parameters.getOrDefault("email_list", "");
                log.info("Sending mail to list {}", emailList);
                
                EmailList el = EmailList.findByCode(emailList);
                if ( el == null ) {
                    throw new IllegalStateException("Lista email non valida : " + emailList);
                } 

                if (el.isUsable()) {
                    toAddresses.addAll(el.getToAddresses());
                    ccAddresses.addAll(el.getCcAddresses());
                    bccAddresses.addAll(el.getBccAddresses());
                }
            }
        } else {
            for (EmailList list : lists) {
                if (list.isUsable()) {
                    toAddresses.addAll(list.getToAddresses());
                    ccAddresses.addAll(list.getCcAddresses());
                    bccAddresses.addAll(list.getBccAddresses());
                }
            }
        }
    
        if (toAddresses.isEmpty()) {
            throw new IllegalStateException("Nessun destinatario TO nelle liste associate");
        }

        // Crea log di invio
        EmailSendLog log = createSendLog(
            template,
            ((singleMail || emailListSpecified) ? null : lists.get(0).getInteger("id_list")), // Prima lista per tracking
            toAddresses,
            ccAddresses,
            bccAddresses,
            renderedSubject,
            renderedBody,
            variables,
            entityType,
            entityId,
            sentBy
        );

        try {
            // Aggiungi footer al body
            String bodyWithFooter = addFooterToBody(renderedBody, template.getBoolean("is_html"));

            // Gestione sicura dei parametri - può essere Map o String (JSON non parsato)
            Map<String, Object> parameters = null;
            Object parametersObj = variables.getOrDefault("parameters", null);

            if (parametersObj instanceof Map) {
                parameters = (Map<String, Object>) parametersObj;
                logger.debug("Parameters already parsed as Map");
            } else if (parametersObj instanceof String) {
                // Se è una stringa, prova a parsarla come JSON
                String parametersStr = (String) parametersObj;
                logger.info("###DEBUG### Parameters is String, attempting to parse: [{}]", parametersStr);

                try {
                    // Prova prima il parsing diretto
                    parameters = objectMapper.readValue(parametersStr, Map.class);
                    logger.info("###DEBUG### Successfully parsed parameters from JSON string (direct)");
                } catch (Exception e1) {
                    logger.warn("###DEBUG### Direct parse failed: {}", e1.getMessage());

                    // Se fallisce, la stringa è probabilmente escaped (contiene \" invece di ")
                    // Rimuovi gli escape e riprova
                    try {
                        String unescaped = parametersStr.replace("\\\"", "\"");
                        logger.info("###DEBUG### Trying with unescaped string: [{}]", unescaped);
                        parameters = objectMapper.readValue(unescaped, Map.class);
                        logger.info("###DEBUG### Successfully parsed parameters after unescaping");
                    } catch (Exception e2) {
                        logger.warn("###DEBUG### Could not parse parameters even after unescaping: {}", e2.getMessage());
                    }
                }
            }

            // Gestione allegato se presente
            EmailAttachment attachment = null;
            String attachmentId = parameters != null ? (String) parameters.get("attachment_id") : null;

            if (attachmentId != null && !attachmentId.trim().isEmpty()) {
                try {
                    logger.info("Download attachment ID={} per template={}", attachmentId, templateId);
                    attachment = downloadAttachment(attachmentId);
                    logger.info("Attachment scaricato con successo: {}", attachment.getFilename());
                } catch (Exception e) {
                    logger.error("Errore download attachment ID={}: {}. Email sarà inviata senza allegato.",
                        attachmentId, e.getMessage(), e);
                    // Continua senza allegato invece di fallire completamente
                }
            }

            // Invia email via SMTP
            String messageId = sendEmail(
                toAddresses,
                ccAddresses,
                bccAddresses,
                renderedSubject,
                bodyWithFooter,
                template.getBoolean("is_html"),
                attachment
            );

            // Marca log come inviato
            log.markAsSent(messageId);

            logger.info("Email inviata con successo: template={}, destinatari={}, messageId={}, attachment={}",
                template.getString("template_code"), toAddresses, messageId,
                attachment != null ? attachment.getFilename() : "none");

        } catch (Exception e) {
            // Marca log come fallito
            log.markAsFailed(e.getMessage());

            logger.error("Errore invio email: template={}, error={}",
                template.getString("template_code"), e.getMessage(), e);

            throw e;
        }

        return (Integer) log.getId();
    }

    /**
     * Invia email personalizzata a destinatari specifici (non da template)
     *
     * @param to Array indirizzi destinatari principali
     * @param cc Array indirizzi in copia conoscenza (opzionale)
     * @param bcc Array indirizzi in copia nascosta (opzionale)
     * @param subject Oggetto email
     * @param body Corpo email
     * @param isHtml Se true, body è HTML; se false, plain text
     * @param sentBy Username utente che invia
     * @return ID del log creato
     * @throws Exception se errore durante invio
     */
    public Integer sendCustomEmail(
            Set<String> to,
            Set<String> cc,
            Set<String> bcc,
            String subject,
            String body,
            boolean isHtml,
            String sentBy) throws Exception {

        logger.info("Invio email personalizzata per utente {}", sentBy);

        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("Almeno un destinatario TO è richiesto");
        }

        // Crea log di invio
        EmailSendLog log = createSendLog(
            null,
            null,
            to,
            cc,
            bcc,
            subject,
            body,
            null,
            null,
            null,
            sentBy
        );

        try {
            // Aggiungi footer al body
            String bodyWithFooter = addFooterToBody(body, isHtml);

            // Invia email via SMTP
            String messageId = sendEmail(to, cc, bcc, subject, bodyWithFooter, isHtml);

            // Marca log come inviato
            log.markAsSent(messageId);

            logger.info("Email personalizzata inviata con successo: destinatari={}, messageId={}",
                to.size(), messageId);

        } catch (Exception e) {
            // Marca log come fallito
            log.markAsFailed(e.getMessage());

            logger.error("Errore invio email personalizzata: error={}", e.getMessage(), e);

            throw e;
        }

        return (Integer) log.getId();
    }

    /**
     * Rendering di un template usando Handlebars template engine
     *
     * Supporta:
     * - Variabili semplici: {{variableName}}
     * - Oggetti nested: {{object.property}}
     * - Foreach su array: {{#each arrayName}} {{property}} {{/each}}
     * - Tag data/ora: {{now "DD/MM/YYYY"}}
     * - Condizionali: {{#if condition}} ... {{/if}}
     *
     * @param template Testo template Handlebars
     * @param variables Mappa variabili (supporta Object, Map, List per foreach)
     * @return Testo renderizzato
     */
    public String renderTemplate(String template, Map<String, Object> variables) {
        if (template == null) return "";

        log.info("###DEBUG### ===== RENDER TEMPLATE START =====");
        log.info("###DEBUG### template: [{}]", template);
        log.info("###DEBUG### variables keys: {}", variables != null ? variables.keySet() : "null");

        // Stampa struttura variabili in dettaglio
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                Object value = entry.getValue();
                log.info("###DEBUG### Variable [{}]:", entry.getKey());
                log.info("###DEBUG###   Type: {}", value != null ? value.getClass().getName() : "null");
                log.info("###DEBUG###   Value: {}", value);

                // Se è una Map, stampa le chiavi e i valori
                if (value instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) value;
                    log.info("###DEBUG###   Map keys: {}", map.keySet());
                    for (Map.Entry<?, ?> mapEntry : map.entrySet()) {
                        Object mapValue = mapEntry.getValue();
                        log.info("###DEBUG###     [{}] = {} (type: {})",
                            mapEntry.getKey(),
                            mapValue,
                            mapValue != null ? mapValue.getClass().getName() : "null");

                        // Se il valore della map è una List, mostra dettagli
                        if (mapValue instanceof List) {
                            List<?> innerList = (List<?>) mapValue;
                            log.info("###DEBUG###       List size: {}", innerList.size());
                            if (!innerList.isEmpty()) {
                                log.info("###DEBUG###       First element: {}", innerList.get(0));
                            }
                        }
                    }
                }
                // Se è una List, stampa dimensione e primo elemento
                if (value instanceof List) {
                    List<?> list = (List<?>) value;
                    log.info("###DEBUG###   List size: {}", list.size());
                    if (!list.isEmpty()) {
                        log.info("###DEBUG###   First element type: {}", list.get(0).getClass().getName());
                        log.info("###DEBUG###   First element: {}", list.get(0));
                    }
                }
            }
        }

        try {
            // Pre-processa il template per convertire sintassi custom {{now:formato}} in {{now "formato"}}
            String preprocessedTemplate = preprocessNowTags(template);
            log.info("###DEBUG### Preprocessed template: [{}]", preprocessedTemplate);

            // Compila il template Handlebars
            Template compiledTemplate = handlebars.compileInline(preprocessedTemplate);

            // Applica le variabili al template
            String result = compiledTemplate.apply(variables != null ? variables : new HashMap<>());

            log.info("###DEBUG### Render result: [{}]", result);
            log.info("###DEBUG### ===== RENDER TEMPLATE END =====");
            log.debug("Template renderizzato con successo");
            return result;

        } catch (IOException e) {
            logger.error("###DEBUG### Errore durante il rendering del template: {}", e.getMessage(), e);
            log.info("###DEBUG### ===== RENDER TEMPLATE END (ERROR) =====");
            // In caso di errore, ritorna il template originale
            return template;
        }
    }

    /**
     * Pre-processa i tag {{now:formato}} convertendoli in sintassi Handlebars {{now "formato"}}
     *
     * Converte:
     * - {{now:DD/MM/YYYY}} → {{now "DD/MM/YYYY"}}
     * - {{now:YYYY-MM-DD HH:mm:ss}} → {{now "YYYY-MM-DD HH:mm:ss"}}
     *
     * @param template Template con sintassi custom {{now:formato}}
     * @return Template con sintassi Handlebars standard
     */
    private String preprocessNowTags(String template) {
        if (template == null) return "";

        // Pattern per trovare {{now:formato}}
        Pattern nowPattern = Pattern.compile("\\{\\{now:([^}]+)\\}\\}");
        Matcher matcher = nowPattern.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String format = matcher.group(1);
            // Converti in sintassi Handlebars: {{now "formato"}}
            String replacement = "{{now \"" + format + "\"}}";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Estrae tutte le variabili presenti in un template Handlebars
     * Nota: Con Handlebars questo metodo è meno utile, ma mantenuto per backward compatibility
     *
     * @param template Testo template
     * @return Set di nomi variabili (senza {{}})
     */
    public Set<String> extractVariables(String template) {
        Set<String> variables = new LinkedHashSet<>();
        if (template == null) return variables;

        // Pattern per variabili semplici: {{variableName}} o {{object.property}}
        Pattern pattern = Pattern.compile("\\{\\{([a-zA-Z0-9_\\.]+)\\}\\}");
        Matcher matcher = pattern.matcher(template);

        while (matcher.find()) {
            variables.add(matcher.group(1));
        }

        return variables;
    }

    /**
     * Anteprima rendering template con dati di esempio
     *
     * @param templateId ID template
     * @param sampleVariables Variabili di esempio per preview (supporta array e oggetti nested)
     * @return Map con subject e body renderizzati
     */
    public Map<String, String> previewTemplate(Integer templateId, Map<String, Object> sampleVariables) {
        EmailTemplate template = EmailTemplate.findById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("Template non trovato: " + templateId);
        }

        Map<String, String> preview = new HashMap<>();
        preview.put("subject", renderTemplate(template.getString("subject"), sampleVariables));
        preview.put("body", renderTemplate(template.getString("body"), sampleVariables));
        preview.put("is_html", String.valueOf(template.getBoolean("is_html")));

        return preview;
    }

    /**
     * Invia effettivamente l'email via SMTP
     */
    private String sendEmail(
            Set<String> to,
            Set<String> cc,
            Set<String> bcc,
            String subject,
            String body,
            boolean isHtml) throws Exception {
        return sendEmail(to, cc, bcc, subject, body, isHtml, null);
    }

    /**
     * Invia effettivamente l'email via SMTP con supporto allegati
     */
    private String sendEmail(
            Set<String> to,
            Set<String> cc,
            Set<String> bcc,
            String subject,
            String body,
            boolean isHtml,
            EmailAttachment attachment) throws Exception {

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        // Mittente
        helper.setFrom(fromAddress, fromName);

        // Destinatari TO
        helper.setTo(to.toArray(new String[0]));

        // Destinatari CC (opzionale)
        if (cc != null && !cc.isEmpty()) {
            helper.setCc(cc.toArray(new String[0]));
        }

        // Destinatari BCC (opzionale)
        if (bcc != null && !bcc.isEmpty()) {
            helper.setBcc(bcc.toArray(new String[0]));
        }

        // Subject e body
        helper.setSubject(subject);
        helper.setText(body, isHtml);

        // Aggiungi allegato se presente
        if (attachment != null) {
            ByteArrayResource resource = new ByteArrayResource(attachment.getData());
            helper.addAttachment(attachment.getFilename(), resource, attachment.getContentType());
            logger.info("Allegato aggiunto all'email: filename={}, size={} bytes",
                attachment.getFilename(), attachment.getData().length);
        }

        // Invia
        mailSender.send(message);

        // Restituisce message ID (se disponibile)
        return message.getMessageID();
    }

    /**
     * Crea record di log per invio email
     */
    private EmailSendLog createSendLog(
            EmailTemplate template,
            Integer listId,
            Set<String> to,
            Set<String> cc,
            Set<String> bcc,
            String subject,
            String body,
            Map<String, Object> variables,
            String entityType,
            Integer entityId,
            String sentBy) {

        try {
            // Converti indirizzi in JSON
            String toJson = to != null ? objectMapper.writeValueAsString(new ArrayList<>(to)) : null;
            String ccJson = cc != null && !cc.isEmpty() ? objectMapper.writeValueAsString(new ArrayList<>(cc)) : null;
            String bccJson = bcc != null && !bcc.isEmpty() ? objectMapper.writeValueAsString(new ArrayList<>(bcc)) : null;
            String variablesJson = variables != null ? objectMapper.writeValueAsString(variables) : null;

            return EmailSendLog.createLog(
                template != null ? (Integer) template.getId() : null,
                template != null ? template.getString("template_code") : null,
                listId,
                toJson,
                ccJson,
                bccJson,
                subject,
                body,
                variablesJson,
                entityType,
                entityId,
                sentBy
            );

        } catch (JsonProcessingException e) {
            logger.error("Errore serializzazione JSON per log email", e);
            throw new RuntimeException("Errore creazione log email", e);
        }
    }

    /**
     * Ritenta invio email fallite (da chiamare in job schedulato)
     *
     * @param maxRetries Numero massimo tentativi
     * @return Numero email ritentate con successo
     */
    public int retryFailedEmails(int maxRetries) {
        List<EmailSendLog> retryable = EmailSendLog.findRetryable(maxRetries);

        int successCount = 0;

        for (EmailSendLog log : retryable) {
            try {
                // Ricostruisci destinatari da JSON
                Set<String> to = parseJsonArray(log.getString("recipients_to"));
                Set<String> cc = parseJsonArray(log.getString("recipients_cc"));
                Set<String> bcc = parseJsonArray(log.getString("recipients_bcc"));

                // Ritenta invio
                String messageId = sendEmail(
                    to, cc, bcc,
                    log.getString("subject"),
                    log.getString("body"),
                    false // Assume plain text per retry
                );

                log.markAsSent(messageId);
                successCount++;

                logger.info("Email ritentata con successo: log_id={}", log.getId());

            } catch (Exception e) {
                log.markForRetry(e.getMessage());
                logger.error("Errore retry email: log_id={}, error={}", log.getId(), e.getMessage());
            }
        }

        logger.info("Retry completato: {}/{} email inviate", successCount, retryable.size());
        return successCount;
    }

    /**
     * Helper per parsing JSON array di stringhe
     */
    private Set<String> parseJsonArray(String json) {
        if (json == null || json.isEmpty()) {
            return new LinkedHashSet<>();
        }

        try {
            List<String> list = objectMapper.readValue(json, List.class);
            return new LinkedHashSet<>(list);
        } catch (JsonProcessingException e) {
            logger.error("Errore parsing JSON array: {}", json, e);
            return new LinkedHashSet<>();
        }
    }

    /**
     * Aggiunge il footer configurato al body dell'email
     *
     * @param body Body dell'email
     * @param isHtml Se true, il body è HTML; se false, plain text
     * @return Body con footer aggiunto
     */
    private String addFooterToBody(String body, boolean isHtml) {
        if (emailFooter == null || emailFooter.trim().isEmpty()) {
            return body;
        }

        if (isHtml) {
            // Per email HTML, aggiungi il footer in HTML
            String htmlFooter = "<br><br><hr><div style='font-size: 0.9em; color: #666;'>" +
                emailFooter.replace("\n", "<br>") +
                "</div>";

            // Se il body contiene </body>, inserisci prima di esso
            if (body.contains("</body>")) {
                return body.replace("</body>", htmlFooter + "</body>");
            } else {
                // Altrimenti aggiungi alla fine
                return body + htmlFooter;
            }
        } else {
            // Per email plain text, aggiungi il footer con newline
            return body + "\n\n" + emailFooter;
        }
    }

    /**
     * Scarica un allegato dall'API backend
     *
     * @param attachmentId ID dell'attachment da scaricare
     * @return EmailAttachment con i dati del file scaricato
     * @throws Exception se errore durante il download
     */
    private EmailAttachment downloadAttachment(String attachmentId) throws Exception {
        if (attachmentId == null || attachmentId.trim().isEmpty()) {
            throw new IllegalArgumentException("ID attachment non valido");
        }

        try {
            // Costruisci URL dell'endpoint
            String url = backendApiUrl + attachmentDownloadEndpoint.replace("{id}", attachmentId);

            logger.info("Scaricamento attachment ID={} da URL={}", attachmentId, url);

            // Prepara headers con X-API-Key
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", backendApiKey);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_OCTET_STREAM));

            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Effettua chiamata REST
            ResponseEntity<byte[]> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                byte[].class
            );

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                throw new IOException("Errore scaricamento attachment: status=" + response.getStatusCode());
            }

            // Estrai filename dal Content-Disposition header
            String filename = extractFilenameFromHeaders(response.getHeaders());
            if (filename == null || filename.isEmpty()) {
                filename = "attachment_" + attachmentId;
            }

            // Estrai content type
            String contentType = response.getHeaders().getContentType() != null
                ? response.getHeaders().getContentType().toString()
                : "application/octet-stream";

            logger.info("Attachment scaricato: filename={}, size={} bytes, contentType={}",
                filename, response.getBody().length, contentType);

            return new EmailAttachment(response.getBody(), filename, contentType);

        } catch (Exception e) {
            logger.error("Errore download attachment ID={}: {}", attachmentId, e.getMessage(), e);
            throw new Exception("Impossibile scaricare l'allegato: " + e.getMessage(), e);
        }
    }

    /**
     * Estrae il filename dall'header Content-Disposition
     *
     * @param headers Headers della risposta HTTP
     * @return Filename estratto o null se non trovato
     */
    private String extractFilenameFromHeaders(HttpHeaders headers) {
        String contentDisposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        if (contentDisposition != null && contentDisposition.contains("filename=")) {
            // Estrai filename da: attachment; filename="document.pdf"
            String[] parts = contentDisposition.split("filename=");
            if (parts.length > 1) {
                String filename = parts[1].trim();
                // Rimuovi virgolette se presenti
                filename = filename.replaceAll("^\"|\"$", "");
                return filename;
            }
        }
        return null;
    }
}
