package com.containermgmt.tfpeventingester.stream;

import com.containermgmt.tfpeventingester.service.BerlinkLookupService;
import com.containermgmt.tfpeventingester.service.BerlinkLookupService.LookupResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Model;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Base class for stream processors. Implements the Template Method pattern:
 * common flow (dedup, parse, lookup, save) in process(), subclasses provide buildModel().
 */
@Slf4j
public abstract class AbstractStreamProcessor implements StreamProcessor {

    protected final ObjectMapper objectMapper;
    protected final BerlinkLookupService berlinkLookupService;
    private final String streamKey;
    private final String consumerGroup;

    protected AbstractStreamProcessor(ObjectMapper objectMapper,
                                       BerlinkLookupService berlinkLookupService,
                                       String streamKey,
                                       String consumerGroup) {
        this.objectMapper = objectMapper;
        this.berlinkLookupService = berlinkLookupService;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
    }

    @Override
    public final String streamKey() {
        return streamKey;
    }

    @Override
    public final String consumerGroup() {
        return consumerGroup;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final void process(Map<String, String> fields) {
        String messageId = fields.get("message_id");
        String eventType = fields.get("event_type");
        String payloadJson = fields.get("payload");

        if (messageId == null || messageId.isBlank()) {
            log.warn("Skipping {} message with null/empty message_id", processorName());
            return;
        }

        // Check metadata for resend flag
        boolean resend = parseResendFlag(fields.get("metadata"));

        if (resend) {
            int deleted = deleteByMessageId(messageId);
            log.info("Resend requested: deleted {} existing {} record(s) for message_id={}",
                    deleted, processorName(), messageId);
        } else {
            if (existsByMessageId(messageId)) {
                log.debug("Duplicate {} message_id={}, skipping", processorName(), messageId);
                return;
            }
        }

        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(payloadJson, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse payload JSON for {} message_id={}: {}",
                    processorName(), messageId, e.getMessage());
            return;
        }

        List<Model> models = buildModels(messageId, eventType, payload);
        if (models.isEmpty()) {
            return;
        }

        // BERLink lookup (once per message)
        String unitNumber = getString(payload, "unitNumber");
        String unitTypeCode = getString(payload, "unitTypeCode");
        LookupResult lookup = berlinkLookupService.lookupUnit(unitNumber, unitTypeCode);

        for (Model model : models) {
            if (lookup.hasData()) {
                model.set("container_number", lookup.containerNumber());
                model.set("id_trailer", lookup.idTrailer());
                model.set("id_vehicle", lookup.idVehicle());
            }
            model.saveIt();
        }
        log.info("Persisted {} {} record(s): message_id={}, unit_number={}",
                models.size(), processorName(), messageId, unitNumber);
    }

    // --- Abstract methods for subclasses ---

    protected abstract Model buildModel(String messageId, String eventType, Map<String, Object> payload);

    /**
     * Hook for processors that need to produce multiple models from a single message.
     * Default wraps buildModel() in a single-element list (backward-compatible).
     */
    protected List<Model> buildModels(String messageId, String eventType, Map<String, Object> payload) {
        Model model = buildModel(messageId, eventType, payload);
        return model != null ? List.of(model) : List.of();
    }

    protected abstract boolean existsByMessageId(String messageId);

    protected abstract int deleteByMessageId(String messageId);

    protected abstract String processorName();

    // --- Helper methods ---

    protected String getString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value != null ? value.toString() : null;
    }

    protected Timestamp parseTimestamp(Map<String, Object> payload, String key) {
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

    protected BigDecimal parseBigDecimal(Map<String, Object> payload, String key) {
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

    @SuppressWarnings("unchecked")
    protected boolean parseResendFlag(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(metadataJson, Map.class);
            Object resend = metadata.get("resend");
            return Boolean.TRUE.equals(resend) || "true".equalsIgnoreCase(String.valueOf(resend));
        } catch (Exception e) {
            log.warn("Failed to parse metadata JSON, treating as no resend: {}", e.getMessage());
            return false;
        }
    }

    protected Boolean getBoolean(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    protected Integer getInteger(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Could not parse Integer for key={}, value={}: {}", key, value, e.getMessage());
            return null;
        }
    }
}
