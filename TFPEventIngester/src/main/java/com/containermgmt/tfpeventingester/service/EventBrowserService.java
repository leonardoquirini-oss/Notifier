package com.containermgmt.tfpeventingester.service;

import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Base;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class EventBrowserService {

    private static final int PAGE_SIZE = 50;

    private final DataSource dataSource;

    public EventBrowserService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public int getPageSize() {
        return PAGE_SIZE;
    }

    // --- Unit Events ---

    public List<Map<String, Object>> searchUnitEvents(String unitNumber, String unitTypeCode,
                                                       LocalDate dateFrom, LocalDate dateTo,
                                                       boolean unlinkedOnly, int page) {
        try {
            Base.open(dataSource);

            StringBuilder sql = new StringBuilder(
                    "SELECT id_unit_event, message_id, message_type, type, event_time, create_time, " +
                    "unit_number, unit_type_code, severity, damage_type, report_notes, " +
                    "latitude, longitude, container_number, id_trailer, id_vehicle " +
                    "FROM evt_unit_events");
            List<Object> params = new ArrayList<>();

            appendUnitEventsWhere(sql, params, unitNumber, unitTypeCode, dateFrom, dateTo, unlinkedOnly);

            sql.append(" ORDER BY event_time DESC LIMIT ? OFFSET ?");
            params.add(PAGE_SIZE);
            params.add(page * PAGE_SIZE);

            return Base.findAll(sql.toString(), params.toArray());
        } finally {
            Base.close();
        }
    }

    public long countUnitEvents(String unitNumber, String unitTypeCode,
                                LocalDate dateFrom, LocalDate dateTo, boolean unlinkedOnly) {
        try {
            Base.open(dataSource);

            StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS cnt FROM evt_unit_events");
            List<Object> params = new ArrayList<>();

            appendUnitEventsWhere(sql, params, unitNumber, unitTypeCode, dateFrom, dateTo, unlinkedOnly);

            Object result = Base.firstCell(sql.toString(), params.toArray());
            return result != null ? ((Number) result).longValue() : 0;
        } finally {
            Base.close();
        }
    }

    // --- Unit Positions ---

    public List<Map<String, Object>> searchUnitPositions(String unitNumber, String unitTypeCode,
                                                          LocalDate dateFrom, LocalDate dateTo,
                                                          boolean unlinkedOnly, int page) {
        try {
            Base.open(dataSource);

            StringBuilder sql = new StringBuilder(
                    "SELECT id_unit_position, message_id, message_type, unit_number, unit_type_code, " +
                    "vehicle_plate, latitude, longitude, position_time, create_time, " +
                    "container_number, id_trailer, id_vehicle " +
                    "FROM evt_unit_positions");
            List<Object> params = new ArrayList<>();

            appendUnitPositionsWhere(sql, params, unitNumber, unitTypeCode, dateFrom, dateTo, unlinkedOnly);

            sql.append(" ORDER BY position_time DESC LIMIT ? OFFSET ?");
            params.add(PAGE_SIZE);
            params.add(page * PAGE_SIZE);

            return Base.findAll(sql.toString(), params.toArray());
        } finally {
            Base.close();
        }
    }

    public long countUnitPositions(String unitNumber, String unitTypeCode,
                                   LocalDate dateFrom, LocalDate dateTo, boolean unlinkedOnly) {
        try {
            Base.open(dataSource);

            StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS cnt FROM evt_unit_positions");
            List<Object> params = new ArrayList<>();

            appendUnitPositionsWhere(sql, params, unitNumber, unitTypeCode, dateFrom, dateTo, unlinkedOnly);

            Object result = Base.firstCell(sql.toString(), params.toArray());
            return result != null ? ((Number) result).longValue() : 0;
        } finally {
            Base.close();
        }
    }

    // --- Dropdowns ---

    public List<String> getDistinctUnitTypeCodes(String tableName) {
        String safeTable = tableName.equals("evt_unit_positions") ? "evt_unit_positions" : "evt_unit_events";
        try {
            Base.open(dataSource);

            List<String> codes = new ArrayList<>();
            List<Map<String, Object>> rows = Base.findAll(
                    "SELECT DISTINCT unit_type_code FROM " + safeTable +
                    " WHERE unit_type_code IS NOT NULL ORDER BY unit_type_code");
            for (Map<String, Object> row : rows) {
                codes.add((String) row.get("unit_type_code"));
            }
            return codes;
        } finally {
            Base.close();
        }
    }

    // --- Where clause builders ---

    private void appendUnitEventsWhere(StringBuilder sql, List<Object> params,
                                       String unitNumber, String unitTypeCode,
                                       LocalDate dateFrom, LocalDate dateTo, boolean unlinkedOnly) {
        List<String> conditions = new ArrayList<>();

        if (unitNumber != null && !unitNumber.isBlank()) {
            conditions.add("unit_number ILIKE ?");
            params.add("%" + unitNumber.trim() + "%");
        }
        if (unitTypeCode != null && !unitTypeCode.isBlank()) {
            conditions.add("unit_type_code = ?");
            params.add(unitTypeCode);
        }
        if (dateFrom != null) {
            conditions.add("event_time >= ?::timestamp");
            params.add(dateFrom.toString());
        }
        if (dateTo != null) {
            conditions.add("event_time < (?::date + interval '1 day')");
            params.add(dateTo.toString());
        }
        if (unlinkedOnly) {
            conditions.add("container_number IS NULL AND id_trailer IS NULL AND id_vehicle IS NULL");
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }

    private void appendUnitPositionsWhere(StringBuilder sql, List<Object> params,
                                           String unitNumber, String unitTypeCode,
                                           LocalDate dateFrom, LocalDate dateTo, boolean unlinkedOnly) {
        List<String> conditions = new ArrayList<>();

        if (unitNumber != null && !unitNumber.isBlank()) {
            conditions.add("unit_number ILIKE ?");
            params.add("%" + unitNumber.trim() + "%");
        }
        if (unitTypeCode != null && !unitTypeCode.isBlank()) {
            conditions.add("unit_type_code = ?");
            params.add(unitTypeCode);
        }
        if (dateFrom != null) {
            conditions.add("position_time >= ?::timestamp");
            params.add(dateFrom.toString());
        }
        if (dateTo != null) {
            conditions.add("position_time < (?::date + interval '1 day')");
            params.add(dateTo.toString());
        }
        if (unlinkedOnly) {
            conditions.add("container_number IS NULL AND id_trailer IS NULL AND id_vehicle IS NULL");
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }
}
