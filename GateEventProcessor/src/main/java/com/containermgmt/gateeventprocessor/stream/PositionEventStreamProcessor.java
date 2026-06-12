package com.containermgmt.gateeventprocessor.stream;

import com.containermgmt.gateeventprocessor.config.PositionEventProperties;
import com.containermgmt.gateeventprocessor.repository.AssetDamageRepository;
import com.containermgmt.gateeventprocessor.service.DailyNotificationThrottle;
import com.containermgmt.gateeventprocessor.service.DamageDetailService;
import com.containermgmt.gateeventprocessor.service.GateMatcher;
import com.containermgmt.gateeventprocessor.service.GateMatcher.Match;
import com.containermgmt.gateeventprocessor.service.NotificationClient;
import com.containermgmt.gateeventprocessor.service.WhatsAppNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Consumes vehicle Position events from stream.position-events, geofences each position
 * against the configured gates and, when a vehicle (identified by vehiclePlate) is inside a
 * gate AND has unresolved OPEN damages, sends a notification — at most once per calendar day
 * per plate (Valkey-tracked via {@link DailyNotificationThrottle}).
 */
@Component
@ConditionalOnProperty("stream.position-events.key")
@Slf4j
public class PositionEventStreamProcessor implements StreamProcessor {

    private static final String NOTIFICATION_TYPE = "gate_in_with_damages";

    private final ObjectMapper objectMapper;
    private final GateMatcher gateMatcher;
    private final NotificationClient notificationClient;
    private final AssetDamageRepository assetDamageRepository;
    private final WhatsAppNotifier whatsAppNotifier;
    private final DailyNotificationThrottle dailyThrottle;
    private final DamageDetailService damageDetailService;
    private final String streamKey;
    private final String consumerGroup;

    public PositionEventStreamProcessor(ObjectMapper objectMapper,
                                        PositionEventProperties props,
                                        GateMatcher gateMatcher,
                                        NotificationClient notificationClient,
                                        AssetDamageRepository assetDamageRepository,
                                        WhatsAppNotifier whatsAppNotifier,
                                        DailyNotificationThrottle dailyThrottle,
                                        DamageDetailService damageDetailService) {
        this.objectMapper = objectMapper;
        this.gateMatcher = gateMatcher;
        this.notificationClient = notificationClient;
        this.assetDamageRepository = assetDamageRepository;
        this.whatsAppNotifier = whatsAppNotifier;
        this.dailyThrottle = dailyThrottle;
        this.damageDetailService = damageDetailService;
        this.streamKey = props.getKey();
        this.consumerGroup = props.getConsumerGroup();
        log.info("PositionEventStreamProcessor configured: stream={}, group={}", streamKey, consumerGroup);
    }

    @Override
    public String streamKey() {
        return streamKey;
    }

    @Override
    public String consumerGroup() {
        return consumerGroup;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Map<String, String> fields) {
        String messageId = fields.get("message_id");
        String payloadJson = fields.get("payload");

        if (messageId == null || messageId.isBlank()) {
            log.warn("Skipping position-event with null/empty message_id");
            return;
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            log.warn("Skipping position-event message_id={}: empty payload", messageId);
            return;
        }

        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(payloadJson, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse payload JSON for position-event message_id={}: {}",
                    messageId, e.getMessage());
            return;
        }

        handlePositionEvent(messageId, payload);
    }

    @SuppressWarnings("unchecked")
    protected void handlePositionEvent(String messageId, Map<String, Object> payload) {
        String vehiclePlate = stringOrNull(payload.get("vehiclePlate"));
        log.info("PositionEvent ENTER: message_id={}, vehiclePlate={}", messageId, vehiclePlate);
        if (vehiclePlate == null || vehiclePlate.isBlank()) {
            log.debug("Skipping position message_id={}: payload.vehiclePlate missing", messageId);
            return;
        }

        Object rawPositions = payload.get("unitPositions");
        if (!(rawPositions instanceof List<?> positions) || positions.isEmpty()) {
            log.debug("Skipping position message_id={}: no unitPositions (plate={})", messageId, vehiclePlate);
            return;
        }

        // Geofence: take the first position that falls inside a configured gate.
        Match match = null;
        for (Object o : positions) {
            if (!(o instanceof Map<?, ?> pos)) {
                continue;
            }
            Double lat = parseDouble(((Map<String, Object>) pos).get("latitude"));
            Double lon = parseDouble(((Map<String, Object>) pos).get("longitude"));
            Match m = gateMatcher.match(lat, lon);
            if (m != null) {
                match = m;
                break;
            }
        }
        if (match == null) {
            log.debug("Skipping position message_id={}: plate={} outside all configured gates",
                    messageId, vehiclePlate);
            return;
        }

        List<Long> damageIds = assetDamageRepository.findUnresolvedOpenDamageIds(vehiclePlate);
        if (damageIds.isEmpty()) {
            log.info("No unresolved OPEN damage for plate={}, skipping notification (message_id={})",
                    vehiclePlate, messageId);
            return;
        }

        // At most one notification per calendar day per plate.
        if (!dailyThrottle.tryAcquireDaily(vehiclePlate)) {
            log.info("Position notification already sent today for plate={}, skipping (message_id={})",
                    vehiclePlate, messageId);
            return;
        }

        String title = "Veicolo " + vehiclePlate + " nei pressi del gate con segnalazioni aperte";
        String link = buildLink(vehiclePlate);
        String notifyGroup = match.gate().getNotifyGroup();

        log.info("Position gate match for message_id={}: plate={}, gate={}, distance={}m, group={}",
                messageId, vehiclePlate, match.gateId(), Math.round(match.distanceMeters()), notifyGroup);

        log.info("PositionEvent NOTIFY: plate={}, group={}, damageIds={}, title=\"{}\"",
                vehiclePlate, notifyGroup, damageIds, title);
        notificationClient.send(notifyGroup, NOTIFICATION_TYPE, title, title, link);

        List<AssetDamageRepository.DamageAttachment> damageAttachments =
                assetDamageRepository.findAttachmentsByDamageIds(damageIds);
        List<WhatsAppNotifier.DamageInfo> damageInfos =
                damageDetailService.buildDamageInfos(damageIds);

        whatsAppNotifier.notifyGroup(notifyGroup, null, vehiclePlate, match.gate().getLabel(),
                List.of(), damageAttachments, damageInfos);
    }

    static String buildLink(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return null;
        }
        String sanitized = identifier.replaceAll("[^A-Za-z0-9]", "");
        if (sanitized.isEmpty()) {
            return null;
        }
        return "/gestione-danni?unit=" + URLEncoder.encode(sanitized, StandardCharsets.UTF_8);
    }

    private static String stringOrNull(Object v) {
        return v == null ? null : v.toString();
    }

    private static Double parseDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
