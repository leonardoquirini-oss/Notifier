package com.containermgmt.tfpeventingester.stream;

import com.containermgmt.tfpeventingester.model.EvtUnitPosition;
import com.containermgmt.tfpeventingester.service.BerlinkLookupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Processes messages from tfp-unit-positions-stream and persists
 * them to the evt_unit_positions table via ActiveJDBC.
 */
@Component
@Slf4j
public class UnitPositionStreamProcessor extends AbstractStreamProcessor {

    public UnitPositionStreamProcessor(ObjectMapper objectMapper,
                                        BerlinkLookupService berlinkLookupService,
                                        @Value("${stream.unit-positions.key}") String streamKey,
                                        @Value("${stream.unit-positions.consumer-group}") String consumerGroup) {
        super(objectMapper, berlinkLookupService, streamKey, consumerGroup);
    }

    @Override
    protected Model buildModel(String messageId, String eventType, Map<String, Object> payload) {
        // Not used — buildModels() is overridden instead
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Model> buildModels(String messageId, String eventType, Map<String, Object> payload) {
        List<Map<String, Object>> unitPositions = (List<Map<String, Object>>) payload.get("unitPositions");
        if (unitPositions == null || unitPositions.isEmpty()) {
            log.warn("No unitPositions array in payload for message_id={}, skipping", messageId);
            return List.of();
        }

        List<Model> models = new ArrayList<>(unitPositions.size());
        for (int i = 0; i < unitPositions.size(); i++) {
            Map<String, Object> position = unitPositions.get(i);
            EvtUnitPosition evt = new EvtUnitPosition();
            evt.set("message_id", messageId);
            evt.set("pos_index", i + 1);
            evt.set("message_type", eventType);
            evt.set("unit_number", getString(payload, "unitNumber"));
            evt.set("unit_type_code", getString(payload, "unitTypeCode"));
            evt.set("vehicle_plate", getString(payload, "vehiclePlate"));
            evt.set("unique_unit", getBoolean(payload, "uniqueUnit"));
            evt.set("unique_vehicle", getBoolean(payload, "uniqueVehicle"));
            evt.set("latitude", parseBigDecimal(position, "latitude"));
            evt.set("longitude", parseBigDecimal(position, "longitude"));
            evt.set("position_time", parseTimestamp(position, "positionTime"));
            evt.set("create_time", parseTimestamp(position, "createTime"));
            models.add(evt);
        }
        return models;
    }

    @Override
    protected boolean existsByMessageId(String messageId) {
        return EvtUnitPosition.existsByMessageId(messageId);
    }

    @Override
    protected int deleteByMessageId(String messageId) {
        return EvtUnitPosition.deleteByMessageId(messageId);
    }

    @Override
    protected String processorName() {
        return "unit position";
    }
}
