package com.containermgmt.gateeventprocessor.stream;

import com.containermgmt.gateeventprocessor.config.GateEventProperties;
import com.containermgmt.gateeventprocessor.repository.AssetDamageRepository;
import com.containermgmt.gateeventprocessor.service.BerlinkLookupService;
import com.containermgmt.gateeventprocessor.service.BerlinkLookupService.LookupResult;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Consumes messages from the configured Valkey stream, filters by payload "type",
 * geomatches against configured gates, and forwards a notification when inside a gate.
 */
@Component
@ConditionalOnProperty("stream.gate-events.key")
@Slf4j
public class GateEventStreamProcessor implements StreamProcessor {

    private static final String NOTIFICATION_TYPE = "gate_in_with_damages";

    private final ObjectMapper objectMapper;
    private final GateMatcher gateMatcher;
    private final NotificationClient notificationClient;
    private final BerlinkLookupService berlinkLookupService;
    private final AssetDamageRepository assetDamageRepository;
    private final WhatsAppNotifier whatsAppNotifier;
    private final String streamKey;
    private final String consumerGroup;
    private final Set<String> allowedTypes;

    public GateEventStreamProcessor(ObjectMapper objectMapper,
                                    GateEventProperties props,
                                    GateMatcher gateMatcher,
                                    NotificationClient notificationClient,
                                    BerlinkLookupService berlinkLookupService,
                                    AssetDamageRepository assetDamageRepository,
                                    WhatsAppNotifier whatsAppNotifier) {
        this.objectMapper = objectMapper;
        this.gateMatcher = gateMatcher;
        this.notificationClient = notificationClient;
        this.berlinkLookupService = berlinkLookupService;
        this.assetDamageRepository = assetDamageRepository;
        this.whatsAppNotifier = whatsAppNotifier;
        this.streamKey = props.getKey();
        this.consumerGroup = props.getConsumerGroup();
        this.allowedTypes = normalize(props.getAllowedTypes());
        log.info("GateEventStreamProcessor configured: stream={}, group={}, allowedTypes={}",
                streamKey, consumerGroup, allowedTypes);
        if (allowedTypes.isEmpty()) {
            log.warn("stream.gate-events.allowed-types is empty: every message will be skipped");
        }
    }

    private static Set<String> normalize(List<String> types) {
        Set<String> out = new HashSet<>();
        if (types == null) return out;
        for (String t : types) {
            if (t != null && !t.isBlank()) {
                out.add(t.trim().toUpperCase(Locale.ROOT));
            }
        }
        return out;
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
        String eventType = fields.get("event_type");
        String payloadJson = fields.get("payload");

        if (messageId == null || messageId.isBlank()) {
            log.warn("Skipping gate-event with null/empty message_id");
            return;
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            log.warn("Skipping gate-event message_id={}: empty payload", messageId);
            return;
        }

        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(payloadJson, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse payload JSON for gate-event message_id={}: {}",
                    messageId, e.getMessage());
            return;
        }

        Object typeRaw = payload.get("type");
        String type = typeRaw != null ? typeRaw.toString() : null;
        if (type == null) {
            log.debug("Skipping message_id={}: payload.type is null", messageId);
            return;
        }
        if (!allowedTypes.contains(type.trim().toUpperCase(Locale.ROOT))) {
            log.debug("Skipping message_id={}: payload.type='{}' not in allowedTypes={}",
                    messageId, type, allowedTypes);
            return;
        }

        handleGateEvent(messageId, eventType, type, payload);
    }

    protected void handleGateEvent(String messageId,
                                   String eventType,
                                   String payloadType,
                                   Map<String, Object> payload) {

        String unitNumber = stringOrNull(payload.get("unitNumber"));
        String unitTypeCode = stringOrNull(payload.get("unitTypeCode"));
        String trailerPlate = stringOrNull(payload.get("trailerPlate"));

        LookupResult lookup = berlinkLookupService.lookupUnit(unitNumber, unitTypeCode);
        log.info("BERLink lookup for message_id={}, unitNumber={}, unitTypeCode={} → containerNumber={}, idTrailer={}, idVehicle={}",
                messageId, unitNumber, unitTypeCode,
                lookup.containerNumber(), lookup.idTrailer(), lookup.idVehicle());

        Double lat = parseDouble(payload.get("latitude"));
        Double lon = parseDouble(payload.get("longitude"));
        if (lat == null || lon == null) {
            log.debug("Skipping message_id={}: missing latitude/longitude", messageId);
            return;
        }

        Match match = gateMatcher.match(lat, lon);
        if (match == null) {
            log.debug("Skipping message_id={}: ({}, {}) outside all configured gates", messageId, lat, lon);
            return;
        }

        if (unitNumber == null || unitNumber.isBlank()) {
            log.debug("Skipping message_id={}: payload.unitNumber missing, cannot check damages", messageId);
            return;
        }
        if (!assetDamageRepository.hasUnresolvedOpenDamage(unitNumber)) {
            log.info("No unresolved OPEN damage for asset_identifier={}, skipping notification (message_id={})",
                    unitNumber, messageId);
            return;
        }

        String title = buildTitle(unitNumber, trailerPlate);
        String link = buildLink(unitNumber);

        log.info("Gate match for message_id={}: gate={}, distance={}m, group={}",
                messageId, match.gateId(), Math.round(match.distanceMeters()), match.gate().getNotifyGroup());

        String notifyGroup = match.gate().getNotifyGroup();
        notificationClient.send(notifyGroup, NOTIFICATION_TYPE, title, title, link);

        whatsAppNotifier.notifyGroup(notifyGroup, unitNumber);
    }

    static String buildLink(String unitNumber) {
        if (unitNumber == null || unitNumber.isEmpty()) {
            return null;
        }
        String sanitized = unitNumber.replaceAll("[^A-Za-z0-9]", "");
        if (sanitized.isEmpty()) {
            return null;
        }
        return "/gestione-danni?unit=" + URLEncoder.encode(sanitized, StandardCharsets.UTF_8);
    }

    static String buildTitle(String unitNumber, String trailerPlate) {
        StringBuilder sb = new StringBuilder("Ingresso ");
        if (unitNumber != null && !unitNumber.isEmpty()) {
            sb.append("Unita ").append(unitNumber).append(' ');
        }
        if (trailerPlate != null && !trailerPlate.isEmpty()) {
            sb.append("Targa ").append(trailerPlate).append(' ');
        }
        sb.append("con segnalazioni aperte");
        return sb.toString();
    }

    private static String stringOrNull(Object v) {
        if (v == null) return null;
        String s = v.toString();
        return s;
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
