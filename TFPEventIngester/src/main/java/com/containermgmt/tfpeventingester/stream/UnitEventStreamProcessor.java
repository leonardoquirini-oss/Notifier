package com.containermgmt.tfpeventingester.stream;

import com.containermgmt.tfpeventingester.model.EvtUnitEvent;
import com.containermgmt.tfpeventingester.service.BerlinkLookupService;
import com.containermgmt.tfpeventingester.service.BerlinkLookupService.LookupResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Processes messages from tfp-unit-events-stream and persists
 * them to the evt_unit_events table via ActiveJDBC.
 */
@Component
@Slf4j
public class UnitEventStreamProcessor implements StreamProcessor {

    private final ObjectMapper objectMapper;
    private final BerlinkLookupService berlinkLookupService;

    public UnitEventStreamProcessor(ObjectMapper objectMapper, BerlinkLookupService berlinkLookupService) {
        this.objectMapper = objectMapper;
        this.berlinkLookupService = berlinkLookupService;
    }

    @Override
    public String streamKey() {
        return "tfp-unit-events-stream";
    }

    @Override
    public String consumerGroup() {
        return "tfp-event-ingester-group";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Map<String, String> fields) {
        String messageId = fields.get("message_id");
        String eventType = fields.get("event_type");
        String payloadJson = fields.get("payload");

        if (messageId == null || messageId.isBlank()) {
            log.warn("Skipping message with null/empty message_id");
            return;
        }

        // Deduplication check
        if (EvtUnitEvent.existsByMessageId(messageId)) {
            log.debug("Duplicate message_id={}, skipping", messageId);
            return;
        }

        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(payloadJson, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse payload JSON for message_id={}: {}", messageId, e.getMessage());
            return;
        }

        EvtUnitEvent event = new EvtUnitEvent();
        event.set("message_id", messageId);
        event.set("message_type", eventType);
        event.set("id", getString(payload, "id"));
        event.set("type", getString(payload, "type"));
        event.set("event_time", parseTimestamp(payload, "eventTime"));
        event.set("create_time", parseTimestamp(payload, "createTime"));
        event.set("latitude", parseBigDecimal(payload, "latitude"));
        event.set("longitude", parseBigDecimal(payload, "longitude"));
        event.set("severity", getString(payload, "severity"));
        event.set("unit_number", getString(payload, "unitNumber"));
        event.set("unit_type_code", getString(payload, "unitTypeCode"));
        event.set("damage_type", getString(payload, "damageType"));
        event.set("report_notes", getString(payload, "reportNotes"));

        // BERLink lookup per popolare container_number, id_trailer, id_vehicle
        String unitNumber = getString(payload, "unitNumber");
        String unitTypeCode = getString(payload, "unitTypeCode");
        LookupResult lookup = berlinkLookupService.lookupUnit(unitNumber, unitTypeCode);
        if (lookup.hasData()) {
            event.set("container_number", lookup.containerNumber());
            event.set("id_trailer", lookup.idTrailer());
            event.set("id_vehicle", lookup.idVehicle());
        }

        event.saveIt();
        log.info("Persisted unit event: message_id={}, unit_number={}", messageId, getString(payload, "unitNumber"));
    }

    private String getString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value != null ? value.toString() : null;
    }

    private Timestamp parseTimestamp(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        try {
            Instant instant = Instant.parse(value.toString());
            return Timestamp.from(instant);
        } catch (DateTimeParseException e) {
            log.warn("Could not parse timestamp for key={}, value={}: {}", key, value, e.getMessage());
            return null;
        }
    }

    private BigDecimal parseBigDecimal(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Could not parse BigDecimal for key={}, value={}: {}", key, value, e.getMessage());
            return null;
        }
    }

}
