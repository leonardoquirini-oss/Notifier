package com.containermgmt.gateeventprocessor.service;

import com.containermgmt.gateeventprocessor.config.BerlinkApiConfig;
import com.containermgmt.gateeventprocessor.config.WhatsAppConfig;
import com.containermgmt.gateeventprocessor.repository.NotificationGroupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends WhatsApp messages to every employee of the notify-group that has a phone number:
 *  - one text message announcing the unit gate-in with damages
 *  - one message per event attachment with a BERLink temporary download URL
 *
 * Each attachment carried in the event (assetDamageAttachments, base64 fileContent) is uploaded to
 * BERLink as a temporary file (POST /api/attachments/upload-temporary). The returned temporary ids
 * are the ones used in the WhatsApp URLs, so the links auto-expire (cleanup job) after
 * whatsapp.temp-timeout-minutes.
 *
 * When whatsapp.dry-run=true no REST call is made (neither upload nor send); requests are only logged.
 */
@Service
@Slf4j
public class WhatsAppNotifier {

    private final NotificationGroupRepository groupRepository;
    private final WhatsAppConfig whatsAppConfig;
    private final BerlinkApiConfig berlinkApiConfig;
    private final ObjectMapper objectMapper;
    // Plain template (no X-API-Key interceptor): BERLink upload-temporary authenticates via ?token,
    // and the filter only honours ?token when the X-API-Key header is absent.
    private final RestTemplate restTemplate = new RestTemplate();

    /** An attachment carried in the event: original filename + base64 file content (+ source id for logs). */
    public record EventAttachment(String filename, String base64Content, Object sourceId) {}

    /** A temporary attachment created for this notification: temp id + original filename. */
    private record TempAttachment(Long id, String filename) {}

    public WhatsAppNotifier(NotificationGroupRepository groupRepository,
                            WhatsAppConfig whatsAppConfig,
                            BerlinkApiConfig berlinkApiConfig,
                            ObjectMapper objectMapper) {
        this.groupRepository = groupRepository;
        this.whatsAppConfig = whatsAppConfig;
        this.berlinkApiConfig = berlinkApiConfig;
        this.objectMapper = objectMapper;
    }

    public void notifyGroup(String groupCode, String eventType, String unitNumber,
                            List<EventAttachment> eventAttachments,
                            List<com.containermgmt.gateeventprocessor.repository.AssetDamageRepository.DamageAttachment> damageAttachments) {
        List<String> phones = groupRepository.findPhoneNumbersByGroupCode(groupCode);
        if (phones.isEmpty()) {
            log.info("WhatsApp: no phone numbers found for group_code={}, nothing to send (unit={})",
                    groupCode, unitNumber);
            return;
        }
        log.info("WhatsApp: group_code={} -> {} phone(s) {} (unit={})",
                groupCode, phones.size(), phones, unitNumber);

        int count = eventAttachments != null ? eventAttachments.size() : 0;
        log.info("WhatsApp: {} attachment(s) in event for unit={}", count, unitNumber);

        List<TempAttachment> tempAttachments = createTemporaryAttachments(eventAttachments, unitNumber);

        String type = eventType != null ? eventType.trim().toUpperCase(Locale.ROOT) : null;
        String prefix;
        if ("GATE_IN".equals(type)) {
            prefix = "Ingresso ";
        } else if ("GATE_OUT".equals(type)) {
            prefix = "Uscita ";
        } else {
            prefix = "";
        }
        String text = prefix + "Unita " + unitNumber + " con segnalazioni";

        for (String phone : phones) {
            sendTextMessage(phone, text);
            for (TempAttachment att : tempAttachments) {
                sendAttachmentMessage(phone, att, unitNumber);
            }
            if (damageAttachments != null) {
                for (var att : damageAttachments) {
                    if (att.idDocument() == null) {
                        continue;
                    }
                    sendDocumentMessage(phone, att.idDocument(), unitNumber);
                }
            }
        }
    }

    /**
     * Uploads every event attachment as a BERLink temporary file and returns the temporary ids.
     * The upload always runs (also in dry-run); only the WhatsApp send is gated by dry-run.
     */
    private List<TempAttachment> createTemporaryAttachments(List<EventAttachment> attachments, String unitNumber) {
        List<TempAttachment> result = new ArrayList<>();
        if (attachments == null) {
            return result;
        }
        for (EventAttachment att : attachments) {
            if (att.base64Content() == null || att.base64Content().isBlank()) {
                log.warn("WhatsApp: skipping attachment sourceId={} (empty fileContent), unit={}",
                        att.sourceId(), unitNumber);
                continue;
            }

            Long tempId = uploadTemporary(att, unitNumber);
            if (tempId != null) {
                result.add(new TempAttachment(tempId, att.filename()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Long uploadTemporary(EventAttachment att, String unitNumber) {
        byte[] content;
        try {
            content = Base64.getDecoder().decode(att.base64Content());
        } catch (Exception e) {
            log.warn("WhatsApp: base64 decode failed for attachment sourceId={}, filename={}: {}",
                    att.sourceId(), att.filename(), e.getMessage());
            return null;
        }

        String fileName = att.filename() != null ? att.filename() : ("attachment-" + att.sourceId());
        String token = berlinkApiConfig.getApiKey() == null ? "" : berlinkApiConfig.getApiKey();
        String url = trimBase() + "/api/attachments/upload-temporary?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);

        ByteArrayResource fileResource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        body.add("entityType", whatsAppConfig.getTempEntityType());
        body.add("entityId", String.valueOf(att.sourceId()));
        body.add("timeout", String.valueOf(whatsAppConfig.getTempTimeoutMinutes()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("WhatsApp upload-temporary REQUEST: POST {} | Content-Type={} | form: file=<{} bytes, filename={}>, "
                        + "entityType={}, entityId={}, timeout={}",
                url, MediaType.MULTIPART_FORM_DATA_VALUE, content.length, fileName,
                whatsAppConfig.getTempEntityType(), att.sourceId(), whatsAppConfig.getTempTimeoutMinutes());

        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, request, Map.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("WhatsApp: upload-temporary failed for sourceId={}: status={}",
                        att.sourceId(), resp.getStatusCode());
                return null;
            }
            Object data = resp.getBody().get("data");
            if (!(data instanceof Map)) {
                log.warn("WhatsApp: upload-temporary response missing 'data' for sourceId={}", att.sourceId());
                return null;
            }
            Object id = ((Map<?, ?>) data).get("id_attachment");
            if (id == null) {
                id = ((Map<?, ?>) data).get("id_document");
            }
            if (id == null) {
                log.warn("WhatsApp: upload-temporary response missing id for sourceId={}, data={}",
                        att.sourceId(), data);
                return null;
            }
            Long tempId = ((Number) id).longValue();
            log.info("WhatsApp: temporary attachment created tempId={} (sourceId={}, filename={})",
                    tempId, att.sourceId(), fileName);
            return tempId;
        } catch (Exception e) {
            log.warn("WhatsApp: upload-temporary failed for sourceId={}: {}", att.sourceId(), e.getMessage());
            return null;
        }
    }

    private void sendTextMessage(String phone, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", phone);
        body.put("text", text);
        body.put("account", whatsAppConfig.getAccount());
        dispatch(whatsAppConfig.getUrl(), body);
    }

    private void sendAttachmentMessage(String phone, TempAttachment att, String unitNumber) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", phone);
        body.put("text", unitNumber);
        body.put("url", buildDownloadUrl(att.id()));
        body.put("caption", unitNumber);
        dispatch(whatsAppConfig.getImageUrl(), body);
    }

    private String buildDownloadUrl(Long tempId) {
        String token = berlinkApiConfig.getApiKey() == null ? "" : berlinkApiConfig.getApiKey();
        return trimBase() + "/api/attachments/temporary/" + tempId + "/download?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    /** Sends an existing BERLink document (evt_damage_attachment.id_document) via its permanent download URL. */
    private void sendDocumentMessage(String phone, Long idDocument, String unitNumber) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", phone);
        body.put("text", unitNumber);
        body.put("url", buildDocumentDownloadUrl(idDocument));
        body.put("caption", unitNumber);
        dispatch(whatsAppConfig.getImageUrl(), body);
    }

    private String buildDocumentDownloadUrl(Long idDocument) {
        String token = berlinkApiConfig.getApiKey() == null ? "" : berlinkApiConfig.getApiKey();
        return trimBase() + "/api/attachments/" + idDocument + "/download?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private String trimBase() {
        return berlinkApiConfig.getBaseUrl() == null ? "" : berlinkApiConfig.getBaseUrl().replaceAll("/+$", "");
    }

    private void dispatch(String targetUrl, Map<String, Object> body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            json = body.toString();
        }

        if (whatsAppConfig.isDryRun()) {
            log.info("WhatsApp [DRY-RUN] POST {} | Authorization: Bearer {} | Content-Type: application/json | body={}",
                    targetUrl, maskToken(whatsAppConfig.getToken()), json);
            return;
        }

        if (targetUrl == null || targetUrl.isBlank()) {
            log.warn("WhatsApp target url not configured; skipping send (body={})", json);
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(whatsAppConfig.getToken());
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    targetUrl, HttpMethod.POST, request, String.class);
            log.info("WhatsApp sent: url={}, http={}, body={}, resp={}",
                    targetUrl, resp.getStatusCode().value(), json, resp.getBody());
        } catch (Exception e) {
            log.error("WhatsApp send failed: url={}, body={}, error={}",
                    targetUrl, json, e.getMessage());
        }
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
