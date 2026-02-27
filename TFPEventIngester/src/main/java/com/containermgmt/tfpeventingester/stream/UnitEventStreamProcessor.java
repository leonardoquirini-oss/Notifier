package com.containermgmt.tfpeventingester.stream;

import com.containermgmt.tfpeventingester.model.EvtUnitEvent;
import com.containermgmt.tfpeventingester.service.BerlinkLookupService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Processes messages from tfp-unit-events-stream and persists
 * them to the evt_unit_events table via ActiveJDBC.
 */
@Component
@Slf4j
public class UnitEventStreamProcessor extends AbstractStreamProcessor {

    public UnitEventStreamProcessor(ObjectMapper objectMapper,
                                     BerlinkLookupService berlinkLookupService,
                                     @Value("${stream.unit-events.key}") String streamKey,
                                     @Value("${stream.unit-events.consumer-group}") String consumerGroup) {
        super(objectMapper, berlinkLookupService, streamKey, consumerGroup);
    }

    @Override
    protected Model buildModel(String messageId, String eventType, Map<String, Object> payload) {
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
        try {
            event.set("payload", objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize payload JSON for message_id={}: {}", messageId, e.getMessage());
        }
        return event;
    }

    @Override
    protected boolean existsByMessageId(String messageId) {
        return EvtUnitEvent.existsByMessageId(messageId);
    }

    @Override
    protected int deleteByMessageId(String messageId) {
        return EvtUnitEvent.deleteByMessageId(messageId);
    }

    @Override
    protected String processorName() {
        return "unit event";
    }
}
