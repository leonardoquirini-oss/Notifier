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
                                                    LocalDate dateTo, int page) {
        try {
            activeJDBCConfig.openConnection();

            StringBuilder sql = new StringBuilder(
                    "SELECT id_event, message_id, event_type, event_time, processed_at, " +
                    "LEFT(payload::text, 200) AS payload_preview, " +
                    "payload::text AS payload_full FROM evt_raw_events");
            List<Object> params = new ArrayList<>();

            appendWhereClause(sql, params, eventType, dateFrom, dateTo);

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
                event.put("payload_preview", row.get("payload_preview"));
                event.put("payload_full", row.get("payload_full"));
                results.add(event);
            }
            return results;
        } finally {
            activeJDBCConfig.closeConnection();
        }
    }

    public long countEvents(String eventType, LocalDate dateFrom, LocalDate dateTo) {
        try {
            activeJDBCConfig.openConnection();

            StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS cnt FROM evt_raw_events");
            List<Object> params = new ArrayList<>();

            appendWhereClause(sql, params, eventType, dateFrom, dateTo);

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

    public int resendEvents(List<Integer> eventIds, boolean forceMessageId) {
        if (eventIds == null || eventIds.isEmpty()) {
            return 0;
        }

        List<Map<String, Object>> events;
        try {
            activeJDBCConfig.openConnection();

            String placeholders = String.join(",",
                    eventIds.stream().map(id -> "?").toList());
            String sql = "SELECT id_event, message_id, event_type, event_time, payload::text AS payload " +
                         "FROM evt_raw_events WHERE id_event IN (" + placeholders + ")";

            events = new ArrayList<>();
            List<Map<String, Object>> rows = Base.findAll(sql, eventIds.toArray());
            for (Map<String, Object> row : rows) {
                Map<String, Object> event = new HashMap<>();
                event.put("id_event", row.get("id_event"));
                event.put("message_id", row.get("message_id"));
                event.put("event_type", row.get("event_type"));
                event.put("event_time", row.get("event_time"));
                event.put("payload", row.get("payload"));
                events.add(event);
            }
        } finally {
            activeJDBCConfig.closeConnection();
        }

        Map<String, Object> metadata = forceMessageId ? Map.of("resend", "true") : null;

        int count = 0;
        for (Map<String, Object> event : events) {
            try {
                Object eventTimeObj = event.get("event_time");
                Instant eventTime = null;
                if (eventTimeObj instanceof java.sql.Timestamp ts) {
                    eventTime = ts.toInstant();
                }

                EventMessage msg = EventMessage.builder()
                        .messageId((String) event.get("message_id"))
                        .eventType((String) event.get("event_type"))
                        .eventTime(eventTime)
                        .rawPayload((String) event.get("payload"))
                        .build();

                valkeyStreamPublisher.publish(msg, metadata);
                count++;
            } catch (Exception e) {
                log.warn("Failed to resend event id={}: {}", event.get("id_event"), e.getMessage());
            }
        }

        log.info("Resent {}/{} events (forceMessageId={})", count, eventIds.size(), forceMessageId);
        return count;
    }

    public int resendAllByFilter(String eventType, LocalDate dateFrom, LocalDate dateTo, boolean forceMessageId) {
        List<Map<String, Object>> events;
        try {
            activeJDBCConfig.openConnection();

            StringBuilder sql = new StringBuilder(
                    "SELECT id_event, message_id, event_type, event_time, payload::text AS payload FROM evt_raw_events");
            List<Object> params = new ArrayList<>();

            appendWhereClause(sql, params, eventType, dateFrom, dateTo);
            sql.append(" ORDER BY event_time DESC");

            events = new ArrayList<>();
            List<Map<String, Object>> rows = Base.findAll(sql.toString(), params.toArray());
            for (Map<String, Object> row : rows) {
                Map<String, Object> event = new HashMap<>();
                event.put("id_event", row.get("id_event"));
                event.put("message_id", row.get("message_id"));
                event.put("event_type", row.get("event_type"));
                event.put("event_time", row.get("event_time"));
                event.put("payload", row.get("payload"));
                events.add(event);
            }
        } finally {
            activeJDBCConfig.closeConnection();
        }

        Map<String, Object> metadata = forceMessageId ? Map.of("resend", "true") : null;

        int count = 0;
        for (Map<String, Object> event : events) {
            try {
                Object eventTimeObj = event.get("event_time");
                Instant eventTime = null;
                if (eventTimeObj instanceof java.sql.Timestamp ts) {
                    eventTime = ts.toInstant();
                }

                EventMessage msg = EventMessage.builder()
                        .messageId((String) event.get("message_id"))
                        .eventType((String) event.get("event_type"))
                        .eventTime(eventTime)
                        .rawPayload((String) event.get("payload"))
                        .build();

                valkeyStreamPublisher.publish(msg, metadata);
                count++;
            } catch (Exception e) {
                log.warn("Failed to resend event id={}: {}", event.get("id_event"), e.getMessage());
            }
        }

        log.info("Resend all by filter: resent {}/{} events (eventType={}, dateFrom={}, dateTo={}, forceMessageId={})",
                count, events.size(), eventType, dateFrom, dateTo, forceMessageId);
        return count;
    }

    private void appendWhereClause(StringBuilder sql, List<Object> params,
                                    String eventType, LocalDate dateFrom, LocalDate dateTo) {
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

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }
}
