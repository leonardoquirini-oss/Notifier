package com.containermgmt.tfpeventingester.service;

import com.containermgmt.tfpeventingester.config.DamageLabelsProperties;
import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Base;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class EventBrowserService {

    private static final int PAGE_SIZE = 50;

    /** Whitelist of allowed (column, table) combinations for getDistinctValues() */
    private static final Set<String> ALLOWED_DISTINCT_QUERIES = Set.of(
            "unit_type_code:evt_unit_events",
            "unit_type_code:evt_unit_positions",
            "asset_type:evt_asset_damages",
            "severity:evt_asset_damages",
            "status:evt_asset_damages"
    );

    private final DataSource dataSource;
    private final Map<String, String> labelDisplayNames;

    public EventBrowserService(DataSource dataSource, DamageLabelsProperties damageLabelsProperties) {
        this.dataSource = dataSource;
        this.labelDisplayNames = damageLabelsProperties.buildDisplayNames();
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

    // --- Asset Damages ---

    public List<Map<String, Object>> searchAssetDamages(String assetIdentifier, String assetType,
                                                         String severity, String status,
                                                         LocalDate dateFrom, LocalDate dateTo,
                                                         boolean unlinkedOnly, int page) {
        try {
            Base.open(dataSource);

            StringBuilder sql = new StringBuilder(
                    "SELECT d.id_asset_damage, d.message_id, d.asset_type, d.asset_identifier, " +
                    "d.severity, d.status, d.type, d.report_time, d.description, d.report_notes, " +
                    "d.container_number, d.id_trailer, d.id_vehicle, " +
                    // Vehicle label columns
                    "vl.dmg_braking AS vl_braking, vl.dmg_tyres AS vl_tyres, " +
                    "vl.dmg_iso_air AS vl_iso_air, vl.dmg_light AS vl_light, " +
                    "vl.dmg_twist AS vl_twist, vl.dmg_other AS vl_other, " +
                    "vl.dmg_piston AS vl_piston, vl.dmg_stabilizers AS vl_stabilizers, " +
                    "vl.dmg_control AS vl_control, vl.dmg_roller AS vl_roller, " +
                    "vl.dmg_worn_hose AS vl_worn_hose, " +
                    // Unit label columns
                    "ul.dmg_structure AS ul_structure, ul.dmg_doors AS ul_doors, " +
                    "ul.dmg_roof AS ul_roof, ul.dmg_walls AS ul_walls, " +
                    "ul.dmg_twist_lock AS ul_twist_lock, ul.dmg_other AS ul_other, " +
                    "ul.dmg_floor AS ul_floor, ul.dmg_side_shot AS ul_side_shot, " +
                    "ul.dmg_door_handles AS ul_door_handles, ul.dmg_zippers AS ul_zippers, " +
                    "ul.dmg_roof_slats AS ul_roof_slats, ul.dmg_hooks AS ul_hooks, " +
                    "ul.dmg_hatches AS ul_hatches, ul.dmg_handrail AS ul_handrail, " +
                    "ul.dmg_letterbox AS ul_letterbox, ul.dmg_security AS ul_security " +
                    "FROM evt_asset_damages d " +
                    "LEFT JOIN evt_vehicle_damage_labels vl ON vl.id_asset_damage = d.id_asset_damage " +
                    "LEFT JOIN evt_unit_damage_labels ul ON ul.id_asset_damage = d.id_asset_damage");
            List<Object> params = new ArrayList<>();

            appendAssetDamagesWhere(sql, params, assetIdentifier, assetType, severity, status,
                    dateFrom, dateTo, unlinkedOnly);

            sql.append(" ORDER BY d.report_time DESC NULLS LAST LIMIT ? OFFSET ?");
            params.add(PAGE_SIZE);
            params.add(page * PAGE_SIZE);

            List<Map<String, Object>> rows = Base.findAll(sql.toString(), params.toArray());

            // Post-processing: extract active labels for each row
            for (Map<String, Object> row : rows) {
                List<String> activeLabels = new ArrayList<>();
                for (Map.Entry<String, String> entry : labelDisplayNames.entrySet()) {
                    Object val = row.get(entry.getKey());
                    if (Boolean.TRUE.equals(val)) {
                        activeLabels.add(entry.getValue());
                    }
                }
                row.put("activeLabels", activeLabels);
            }

            return rows;
        } finally {
            Base.close();
        }
    }

    public long countAssetDamages(String assetIdentifier, String assetType,
                                   String severity, String status,
                                   LocalDate dateFrom, LocalDate dateTo, boolean unlinkedOnly) {
        try {
            Base.open(dataSource);

            StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS cnt FROM evt_asset_damages d");
            List<Object> params = new ArrayList<>();

            appendAssetDamagesWhere(sql, params, assetIdentifier, assetType, severity, status,
                    dateFrom, dateTo, unlinkedOnly);

            Object result = Base.firstCell(sql.toString(), params.toArray());
            return result != null ? ((Number) result).longValue() : 0;
        } finally {
            Base.close();
        }
    }

    // --- Dropdowns ---

    public List<String> getDistinctValues(String column, String table) {
        String key = column + ":" + table;
        if (!ALLOWED_DISTINCT_QUERIES.contains(key)) {
            throw new IllegalArgumentException("Disallowed distinct query: " + key);
        }
        try {
            Base.open(dataSource);

            List<String> values = new ArrayList<>();
            List<Map<String, Object>> rows = Base.findAll(
                    "SELECT DISTINCT " + column + " FROM " + table +
                    " WHERE " + column + " IS NOT NULL ORDER BY " + column);
            for (Map<String, Object> row : rows) {
                values.add(String.valueOf(row.get(column)));
            }
            return values;
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

    private void appendAssetDamagesWhere(StringBuilder sql, List<Object> params,
                                          String assetIdentifier, String assetType,
                                          String severity, String status,
                                          LocalDate dateFrom, LocalDate dateTo, boolean unlinkedOnly) {
        List<String> conditions = new ArrayList<>();

        if (assetIdentifier != null && !assetIdentifier.isBlank()) {
            conditions.add("d.asset_identifier ILIKE ?");
            params.add("%" + assetIdentifier.trim() + "%");
        }
        if (assetType != null && !assetType.isBlank()) {
            conditions.add("d.asset_type = ?");
            params.add(assetType);
        }
        if (severity != null && !severity.isBlank()) {
            conditions.add("d.severity = ?");
            params.add(severity);
        }
        if (status != null && !status.isBlank()) {
            conditions.add("d.status = ?");
            params.add(status);
        }
        if (dateFrom != null) {
            conditions.add("d.report_time >= ?::timestamp");
            params.add(dateFrom.toString());
        }
        if (dateTo != null) {
            conditions.add("d.report_time < (?::date + interval '1 day')");
            params.add(dateTo.toString());
        }
        if (unlinkedOnly) {
            conditions.add("d.container_number IS NULL AND d.id_trailer IS NULL AND d.id_vehicle IS NULL");
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }
}
