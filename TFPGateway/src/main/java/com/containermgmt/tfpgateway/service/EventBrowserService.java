package com.containermgmt.tfpgateway.service;

import com.containermgmt.tfpgateway.config.ActiveJDBCConfig;
import com.containermgmt.tfpgateway.config.GatewayProperties;
import com.containermgmt.tfpgateway.dto.EventMessage;

import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Base;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class EventBrowserService {

    private static final int PAGE_SIZE = 50;

    private final ActiveJDBCConfig activeJDBCConfig;
    private final ValkeyStreamPublisher valkeyStreamPublisher;
    private final GatewayProperties gatewayProperties;

    public EventBrowserService(ActiveJDBCConfig activeJDBCConfig,
                               ValkeyStreamPublisher valkeyStreamPublisher,
                               GatewayProperties gatewayProperties) {
        this.activeJDBCConfig = activeJDBCConfig;
        this.valkeyStreamPublisher = valkeyStreamPublisher;
        this.gatewayProperties = gatewayProperties;
    }

    public List<Map<String, Object>> searchEvents(String eventType, LocalDate dateFrom,
                                                    LocalDate dateTo, String messageId,
                                                    String unitNumber, String payloadType,
                                                    String additionalData, int page) {
        try {
            activeJDBCConfig.openConnection();

            StringBuilder sql = new StringBuilder(
                    "SELECT id_event, message_id, event_type, event_time, processed_at, additional_data, " +
                    "LEFT(payload::text, 200) AS payload_preview, " +
                    "payload::text AS payload_full FROM evt_raw_events");
            List<Object> params = new ArrayList<>();

            appendWhereClause(sql, params, eventType, dateFrom, dateTo, messageId, unitNumber, payloadType, additionalData);

            sql.append(" ORDER BY event_time DESC LIMIT ? OFFSET ?");
            params.add(PAGE_SIZE);
            params.add(page * PAGE_SIZE);

            List<Map<String, Object>> results = new ArrayList<>();
            List<Map<String, Object>> rows = Base.findAll(sql.toString(), params.toArray());
            for (Map<String, Object> row : rows) {
                Map<String, Object> event = new HashMap<>();
                event.put("id_event", row.get("id_event"));
                event.put("message_id", row.get("message_id"));
                event.put("event_type", row.get("event_type"));
                event.put("event_time", row.get("event_time"));
                event.put("processed_at", row.get("processed_at"));
                event.put("additional_data", row.get("additional_data"));
                event.put("payload_preview", row.get("payload_preview"));
                event.put("payload_full", row.get("payload_full"));
                results.add(event);
            }
            return results;
        } finally {
            activeJDBCConfig.closeConnection();
        }
    }

    public long countEvents(String eventType, LocalDate dateFrom, LocalDate dateTo, String messageId,
                            String unitNumber, String payloadType, String additionalData) {
        try {
            activeJDBCConfig.openConnection();

            StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS cnt FROM evt_raw_events");
            List<Object> params = new ArrayList<>();

            appendWhereClause(sql, params, eventType, dateFrom, dateTo, messageId, unitNumber, payloadType, additionalData);

            Object result = Base.firstCell(sql.toString(), params.toArray());
            return result != null ? ((Number) result).longValue() : 0;
        } finally {
            activeJDBCConfig.closeConnection();
        }
    }

    public List<String> getDistinctEventTypes() {
        try {
            activeJDBCConfig.openConnection();

            List<String> types = new ArrayList<>();
            List<Map<String, Object>> rows = Base.findAll(
                    "SELECT DISTINCT event_type FROM evt_raw_events ORDER BY event_type");
            for (Map<String, Object> row : rows) {
                types.add((String) row.get("event_type"));
            }
            return types;
        } finally {
            activeJDBCConfig.closeConnection();
        }
    }

    /**
     * Resend size for batched processing.
     * Mantiene il footprint di memoria limitato: solo BATCH_SIZE payload contemporaneamente in heap.
     */
    private static final int RESEND_BATCH_SIZE = 100;

    public int resendEvents(List<Integer> eventIds, boolean forceMessageId) {
        if (eventIds == null || eventIds.isEmpty()) {
            return 0;
        }

        Map<String, Object> metadata = forceMessageId ? Map.of("resend", "true") : null;
        int count = resendInBatches(eventIds, metadata);
        log.info("Resent {}/{} events (forceMessageId={})", count, eventIds.size(), forceMessageId);
        return count;
    }

    public int resendAllByFilter(String eventType, LocalDate dateFrom, LocalDate dateTo,
                                 String messageId, String unitNumber, String payloadType,
                                 String additionalData, boolean forceMessageId) {
        // Phase 1: load only IDs (cheap, no payloads) per evitare OOM su volumi grandi.
        List<Integer> ids;
        try {
            activeJDBCConfig.openConnection();

            StringBuilder sql = new StringBuilder("SELECT id_event FROM evt_raw_events");
            List<Object> params = new ArrayList<>();
            appendWhereClause(sql, params, eventType, dateFrom, dateTo, messageId, unitNumber, payloadType, additionalData);
            sql.append(" ORDER BY event_time DESC");

            List<Map<String, Object>> rows = Base.findAll(sql.toString(), params.toArray());
            ids = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Object v = row.get("id_event");
                if (v instanceof Number n) {
                    ids.add(n.intValue());
                }
            }
        } finally {
            activeJDBCConfig.closeConnection();
        }

        // Phase 2: resend in batches.
        Map<String, Object> metadata = forceMessageId ? Map.of("resend", "true") : null;
        int count = resendInBatches(ids, metadata);

        log.info("Resend all by filter: resent {}/{} events (eventType={}, dateFrom={}, dateTo={}, messageId={}, unitNumber={}, payloadType={}, additionalData={}, forceMessageId={})",
                count, ids.size(), eventType, dateFrom, dateTo, messageId, unitNumber, payloadType, additionalData, forceMessageId);
        return count;
    }

    private int resendInBatches(List<Integer> ids, Map<String, Object> metadata) {
        if (ids == null || ids.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < ids.size(); i += RESEND_BATCH_SIZE) {
            List<Integer> batch = ids.subList(i, Math.min(i + RESEND_BATCH_SIZE, ids.size()));
            total += publishBatch(batch, metadata);
        }
        return total;
    }

    private int publishBatch(List<Integer> batchIds, Map<String, Object> metadata) {
        List<Map<String, Object>> rows;
        try {
            activeJDBCConfig.openConnection();
            String placeholders = String.join(",", batchIds.stream().map(x -> "?").toList());
            String sql = "SELECT id_event, message_id, event_type, event_time, payload::text AS payload " +
                         "FROM evt_raw_events WHERE id_event IN (" + placeholders + ")";
            rows = Base.findAll(sql, batchIds.toArray());
        } finally {
            activeJDBCConfig.closeConnection();
        }

        int count = 0;
        for (Map<String, Object> row : rows) {
            try {
                Object eventTimeObj = row.get("event_time");
                Instant eventTime = null;
                if (eventTimeObj instanceof java.sql.Timestamp ts) {
                    eventTime = ts.toInstant();
                }

                EventMessage msg = EventMessage.builder()
                        .messageId((String) row.get("message_id"))
                        .eventType((String) row.get("event_type"))
                        .eventTime(eventTime)
                        .rawPayload((String) row.get("payload"))
                        .build();

                valkeyStreamPublisher.publish(msg, metadata);
                count++;
            } catch (Exception e) {
                log.warn("Failed to resend event id={}: {}", row.get("id_event"), e.getMessage());
            }
        }
        return count;
    }

    private void appendWhereClause(StringBuilder sql, List<Object> params,
                                    String eventType, LocalDate dateFrom, LocalDate dateTo,
                                    String messageId, String unitNumber, String payloadType,
                                    String additionalData) {
        List<String> conditions = new ArrayList<>();

        if (eventType != null && !eventType.isBlank()) {
            conditions.add("event_type = ?");
            params.add(eventType);
        }
        if (dateFrom != null) {
            conditions.add("event_time >= ?::timestamp");
            params.add(dateFrom.toString());
        }
        if (dateTo != null) {
            conditions.add("event_time < (?::date + interval '1 day')");
            params.add(dateTo.toString());
        }
        if (messageId != null && !messageId.isBlank()) {
            conditions.add("message_id ILIKE ?");
            params.add("%" + messageId + "%");
        }
        if (unitNumber != null && !unitNumber.isBlank()) {
            conditions.add("payload->>'unitNumber' ILIKE ?");
            params.add("%" + unitNumber + "%");
        }
        if (payloadType != null && !payloadType.isBlank()) {
            conditions.add("payload->>'type' ILIKE ?");
            params.add("%" + payloadType + "%");
        }
        if (additionalData != null && !additionalData.isBlank()) {
            conditions.add("additional_data ILIKE ?");
            params.add("%" + additionalData + "%");
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }
}
