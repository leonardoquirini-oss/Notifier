package com.containermgmt.notifier.service;

import com.containermgmt.notifier.config.BerlinkApiConfig;
import com.containermgmt.notifier.config.WhatsAppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Invia su WhatsApp un allegato condiviso (evento attachment:share con campo phone).
 *
 * Il file non viene caricato: si usa il link di download permanente di BERLink con il
 * fallback ?token=<api-key> (vedi ApiKeyAuthenticationFilter, ammesso su GET .../download).
 * Il link punta a {@code berlink.api.base-url} con ?token={@code berlink.api.api-key}: base e
 * chiave devono appartenere alla STESSA istanza, raggiungibile dal servizio WhatsApp esterno
 * (non dall'hostname interno docker http://backend:8080).
 *
 * Con whatsapp.dry-run=true non viene effettuata alcuna chiamata REST: la richiesta e' solo loggata.
 */
@Service
@Slf4j
public class WhatsAppNotifier {

    @Autowired
    private WhatsAppConfig whatsAppConfig;

    @Autowired
    private BerlinkApiConfig berlinkApiConfig;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${backend.api.attachments.download-endpoint}")
    private String attachmentDownloadEndpoint;

    /**
     * Invia su WhatsApp l'allegato condiviso se nei parametri dell'evento e' presente un phone.
     * No-op se phone assente. Non lancia eccezioni: errori loggati per non bloccare l'ACK.
     *
     * @param variables contesto evento (chiave "parameters" -> Map con phone, attachment_id, ...)
     */
    @SuppressWarnings("unchecked")
    public void maybeShare(Map<String, Object> variables) {
        Object parametersObj = variables != null ? variables.get("parameters") : null;
        if (!(parametersObj instanceof Map)) {
            log.debug("WhatsApp: nessun 'parameters' nell'evento, skip");
            return;
        }
        Map<String, Object> parameters = (Map<String, Object>) parametersObj;

        String phone = normalizePhone(asString(parameters.get("phone")));
        if (phone == null || phone.isBlank()) {
            log.debug("WhatsApp: nessun phone nell'evento, skip");
            return;
        }

        String attachmentId = asString(parameters.get("attachment_id"));
        if (attachmentId == null || attachmentId.isBlank()) {
            log.warn("WhatsApp: phone presente ({}) ma attachment_id mancante, skip invio", phone);
            return;
        }

        String filename = asString(parameters.get("original_filename"));
        String sharedBy = asString(parameters.get("shared_by"));

        sendAttachment(phone, attachmentId, filename, sharedBy);
    }

    private void sendAttachment(String phone, String attachmentId, String filename, String sharedBy) {
        String url = buildDownloadUrl(attachmentId);
        log.info("WhatsApp download URL allegato: attachmentId={}, url={}", attachmentId, url);

        Map<String, Object> body = new LinkedHashMap<>();
        String targetUrl;
        if (isImage(filename)) {
            String caption = buildCaption(filename, sharedBy);
            body.put("phone", phone);
            body.put("text", caption);
            body.put("url", url);
            body.put("caption", caption);
            body.put("account", whatsAppConfig.getAccount());
            targetUrl = whatsAppConfig.getImageUrl();
        } else {
            body.put("phone", phone);
            body.put("url", url);
            body.put("filename", (filename != null && !filename.isBlank()) ? filename : "allegato");
            targetUrl = whatsAppConfig.getFileUrl();
        }

        dispatch(targetUrl, body, attachmentId);
    }

    private static final java.util.Set<String> IMAGE_EXTENSIONS = java.util.Set.of(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff", "heic", "heif", "svg");

    private static boolean isImage(String filename) {
        if (filename == null) {
            return false;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return false;
        }
        String ext = filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return IMAGE_EXTENSIONS.contains(ext);
    }

    private String buildCaption(String filename, String sharedBy) {
        String name = (filename != null && !filename.isBlank()) ? filename : "allegato";
        String by = (sharedBy != null && !sharedBy.isBlank()) ? sharedBy : "BERLink";
        return "Documento '" + name + "' condiviso da " + by;
    }

    private String buildDownloadUrl(String attachmentId) {
        String base = berlinkApiConfig.getBaseUrl();
        if (base == null) {
            base = "";
        }
        base = base.replaceAll("/+$", "");
        String path = attachmentDownloadEndpoint.replace("{id}", attachmentId);
        String token = berlinkApiConfig.getApiKey() == null ? "" : berlinkApiConfig.getApiKey();
        return base + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private void dispatch(String targetUrl, Map<String, Object> body, String attachmentId) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            json = body.toString();
        }

        if (whatsAppConfig.isDryRun()) {
            log.info("WhatsApp [DRY-RUN] POST {} | Authorization: Bearer {} | body={}",
                targetUrl, maskToken(whatsAppConfig.getToken()), json);
            return;
        }

        if (targetUrl == null || targetUrl.isBlank()) {
            log.warn("WhatsApp endpoint non configurato (image-url/file-url); skip invio (attachmentId={})", attachmentId);
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(whatsAppConfig.getToken());
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(targetUrl, HttpMethod.POST, request, String.class);
            log.info("WhatsApp inviato: attachmentId={}, http={}, resp={}",
                attachmentId, resp.getStatusCode().value(), resp.getBody());
        } catch (Exception e) {
            log.error("WhatsApp invio fallito: attachmentId={}, body={}, error={}", attachmentId, json, e.getMessage());
        }
    }

    private static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        return phone.replaceAll("\\s+", "");
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "<unset>";
        }
        if (token.length() <= 8) {
            return "****";
        }
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
