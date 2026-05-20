package com.containermgmt.gateeventprocessor.service;

import com.containermgmt.gateeventprocessor.config.BerlinkApiConfig;
import com.containermgmt.gateeventprocessor.config.WhatsAppConfig;
import com.containermgmt.gateeventprocessor.repository.AssetDamageRepository;
import com.containermgmt.gateeventprocessor.repository.AssetDamageRepository.DamageAttachment;
import com.containermgmt.gateeventprocessor.repository.NotificationGroupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Map;

/**
 * Sends WhatsApp messages to every employee of the notify-group that has a phone number:
 *  - one text message announcing the unit gate-in with damages
 *  - one message per damage attachment with the BERLink download URL
 *
 * When whatsapp.dry-run=true the REST call is NOT performed; every request is only logged.
 */
@Service
@Slf4j
public class WhatsAppNotifier {

    private final NotificationGroupRepository groupRepository;
    private final AssetDamageRepository assetDamageRepository;
    private final WhatsAppConfig whatsAppConfig;
    private final BerlinkApiConfig berlinkApiConfig;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public WhatsAppNotifier(NotificationGroupRepository groupRepository,
                            AssetDamageRepository assetDamageRepository,
                            WhatsAppConfig whatsAppConfig,
                            BerlinkApiConfig berlinkApiConfig,
                            ObjectMapper objectMapper) {
        this.groupRepository = groupRepository;
        this.assetDamageRepository = assetDamageRepository;
        this.whatsAppConfig = whatsAppConfig;
        this.berlinkApiConfig = berlinkApiConfig;
        this.objectMapper = objectMapper;
    }

    public void notifyGroup(String groupCode, String unitNumber) {
        List<String> phones = groupRepository.findPhoneNumbersByGroupCode(groupCode);
        if (phones.isEmpty()) {
            log.info("WhatsApp: no phone numbers found for group_code={}, nothing to send (unit={})",
                    groupCode, unitNumber);
            return;
        }
        log.info("WhatsApp: group_code={} -> {} phone(s) {} (unit={})",
                groupCode, phones.size(), phones, unitNumber);

        List<DamageAttachment> attachments = assetDamageRepository.findOpenDamageAttachments(unitNumber);
        log.info("WhatsApp: {} attachment(s) for unit={}", attachments.size(), unitNumber);

        String text = "Ingresso unita' " + unitNumber + " con segnalazioni";

        for (String phone : phones) {
            sendTextMessage(phone, text);
            for (DamageAttachment att : attachments) {
                sendAttachmentMessage(phone, att, unitNumber);
            }
        }
    }

    private void sendTextMessage(String phone, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", phone);
        body.put("text", text);
        body.put("account", whatsAppConfig.getAccount());
        dispatch(body);
    }

    private void sendAttachmentMessage(String phone, DamageAttachment att, String unitNumber) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", phone);
        body.put("text", unitNumber);
        body.put("url", buildDownloadUrl(att.idDocument()));
        body.put("caption", unitNumber);
        dispatch(body);
    }

    private String buildDownloadUrl(Long idDocument) {
        String base = berlinkApiConfig.getBaseUrl() == null ? "" : berlinkApiConfig.getBaseUrl().replaceAll("/+$", "");
        String token = berlinkApiConfig.getApiKey() == null ? "" : berlinkApiConfig.getApiKey();
        return base + "/api/attachments/" + idDocument + "/download?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private void dispatch(Map<String, Object> body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            json = body.toString();
        }

        if (whatsAppConfig.isDryRun()) {
            log.info("WhatsApp [DRY-RUN] POST {} | Authorization: Bearer {} | Content-Type: application/json | body={}",
                    whatsAppConfig.getUrl(), maskToken(whatsAppConfig.getToken()), json);
            return;
        }

        if (whatsAppConfig.getUrl() == null || whatsAppConfig.getUrl().isBlank()) {
            log.warn("whatsapp.url not configured; skipping send (body={})", json);
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(whatsAppConfig.getToken());
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    whatsAppConfig.getUrl(), HttpMethod.POST, request, String.class);
            log.info("WhatsApp sent: http={}, body={}, resp={}",
                    resp.getStatusCode().value(), json, resp.getBody());
        } catch (Exception e) {
            log.error("WhatsApp send failed: url={}, body={}, error={}",
                    whatsAppConfig.getUrl(), json, e.getMessage());
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
