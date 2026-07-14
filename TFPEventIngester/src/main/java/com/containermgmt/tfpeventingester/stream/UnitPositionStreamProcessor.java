package com.containermgmt.tfpeventingester.stream;

import com.containermgmt.tfpeventingester.model.EvtUnitPosition;
import com.containermgmt.tfpeventingester.service.BerlinkLookupService;
import com.containermgmt.tfpeventingester.service.BerlinkLookupService.LookupResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Base;
import org.javalite.activejdbc.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Processes messages from tfp-unit-positions-stream and persists
 * them to the evt_unit_positions table via ActiveJDBC.
 */
@Component
@ConditionalOnProperty("stream.unit-positions.key")
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

        // Fall back to vehiclePlate when unitNumber is absent (vehicle/trailer positions),
        // consistent with getUnitNumberFromPayload() used for the BERLink lookup. Without this
        // the row's unit_number is null and never lands in evt_unit_last_position (PK unit_number).
        String unitNumber = getUnitNumberFromPayload(payload);
        String unitTypeCode = getString(payload, "unitTypeCode");
        String vehiclePlate = getString(payload, "vehiclePlate");

        // Infer VEHICLE when unitTypeCode is null but vehiclePlate is present
        if (unitTypeCode == null && vehiclePlate != null) {
            unitTypeCode = "VEHICLE";
        }

        List<Model> models = new ArrayList<>(unitPositions.size());
        for (int i = 0; i < unitPositions.size(); i++) {
            Map<String, Object> position = unitPositions.get(i);
            EvtUnitPosition evt = new EvtUnitPosition();
            evt.set("message_id", messageId);
            evt.set("pos_index", i + 1);
            evt.set("message_type", eventType);
            evt.set("unit_number", unitNumber);
            evt.set("unit_type_code", unitTypeCode);
            evt.set("vehicle_plate", vehiclePlate);
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

    /**
     * Persists each position then upserts evt_unit_last_position. The conditional
     * UPSERT (event_time > stored) keeps only the most recent position even when the
     * unitPositions[] array is unordered. Wrapped in a single transaction so the
     * position rows and the last-position row commit atomically.
     */
    @Override
    protected void saveModels(List<Model> models, LookupResult lookup) {
        Base.openTransaction();
        try {
            for (Model model : models) {
                Object containerNumber = null;
                if (lookup.hasData()) {
                    containerNumber = lookup.containerNumber();
                    model.set("container_number", containerNumber);
                    model.set("id_trailer", lookup.idTrailer());
                    model.set("id_vehicle", lookup.idVehicle());
                }
                model.saveIt();
                LastPositionUpserter.upsert(
                        model.get("unit_number"),
                        model.get("unit_type_code"),
                        model.get("message_type"),
                        model.getId(),                 // id_unit_event: use id_unit_position (positions have no parent event)
                        model.get("position_time"),
                        model.get("latitude"),
                        model.get("longitude"),
                        containerNumber,
                        model.get("id_trailer"),
                        model.get("id_vehicle"),
                        null, null, null,              // terminal_code, full_empty, operator_code: not in positions
                        "POSITION",                    // event_type: positions have none; column is NOT NULL
                        null,                          // eta: not in positions
                        model.get("message_id"));
            }
            Base.commitTransaction();
        } catch (Exception e) {
            Base.rollbackTransaction();
            throw e;
        }
    }

    @Override
    protected String getUnitTypeCodeFromPayload(Map<String, Object> payload) {
        String unitTypeCode = getString(payload, "unitTypeCode");
        if (unitTypeCode == null && getString(payload, "vehiclePlate") != null) {
            return "VEHICLE";
        }
        return unitTypeCode;
    }

    @Override
    protected String getUnitNumberFromPayload(Map<String, Object> payload) {
        String unitNumber = getString(payload, "unitNumber");
        if (unitNumber == null && getString(payload, "vehiclePlate") != null) {
            return getString(payload, "vehiclePlate");
        }
        return unitNumber;
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
