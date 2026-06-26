package com.containermgmt.eventcontrolprocessor.notify;

import com.containermgmt.eventcontrolprocessor.config.WhatsAppConfig;
import com.containermgmt.eventcontrolprocessor.repository.NotificationGroupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends a plain-text WhatsApp message to every employee of the notify-group that has a phone number.
 * Mirrors GateEventProcessor's WhatsApp integration (bernardini-os send-message endpoint), trimmed to
 * text-only since control situations carry no attachments.
 *
 * When whatsapp.dry-run=true no REST call is made; requests are only logged.
 */
@Service
@Slf4j
public class WhatsAppNotifier {

    private final NotificationGroupRepository groupRepository;
    private final WhatsAppConfig whatsAppConfig;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public WhatsAppNotifier(NotificationGroupRepository groupRepository,
                            WhatsAppConfig whatsAppConfig,
                            ObjectMapper objectMapper) {
        this.groupRepository = groupRepository;
        this.whatsAppConfig = whatsAppConfig;
        this.objectMapper = objectMapper;
    }

    public void notifyGroup(String groupCode, String text) {
        List<String> phones = groupRepository.findPhoneNumbersByGroupCode(groupCode);
        if (phones.isEmpty()) {
            log.info("WhatsApp: no phone numbers found for group_code={}, nothing to send", groupCode);
            return;
        }
        log.info("WhatsApp: group_code={} -> {} phone(s)", groupCode, phones.size());
        for (String phone : phones) {
            sendTextMessage(phone, text);
        }
    }

    private void sendTextMessage(String phone, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", phone);
        body.put("text", text);
        body.put("account", whatsAppConfig.getAccount());
        dispatch(whatsAppConfig.getUrl(), body);
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
            log.info("WhatsApp sent: url={}, http={}, resp={}",
                    targetUrl, resp.getStatusCode().value(), resp.getBody());
        } catch (Exception e) {
            log.error("WhatsApp send failed: url={}, body={}, error={}", targetUrl, json, e.getMessage());
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
