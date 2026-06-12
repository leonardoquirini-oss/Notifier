package com.containermgmt.gateeventprocessor.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Reads from BERLink evt_asset_damages.
 */
@Repository
@Slf4j
public class AssetDamageRepository {

    /**
     * Returns the id_asset_damage of every OPEN damage row for the given asset_identifier
     * whose tfp_event_id has no matching REPAIRED / UNDER_REPAIR row.
     *
     * OPEN rows with tfp_event_id IS NULL are treated as orphan (no closure possible),
     * therefore they also trigger the notification.
     */
    private static final String UNRESOLVED_IDS_QUERY = """
            SELECT d.id_asset_damage
            FROM evt_asset_damages d
            WHERE d.asset_identifier = ?
              AND d.status = 'OPEN'
              AND (
                   d.tfp_event_id IS NULL
                   OR NOT EXISTS (
                        SELECT 1
                        FROM evt_asset_damages d2
                        WHERE d2.tfp_event_id = d.tfp_event_id
                          AND d2.status IN ('REPAIRED', 'UNDER_REPAIR')
                   )
              )
            """;

    private final JdbcTemplate jdbc;

    public AssetDamageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Attachment of a damage row, stored as a BERLink document reference (id_document). */
    public record DamageAttachment(Long idDocument, String filename) {
    }

    /**
     * Detail of a single OPEN damage row used to compose the notification text:
     * asset type, report (opening) notes, and the active checklist columns (e.g. dmg_tyres).
     */
    public record DamageDetail(Long idAssetDamage, String assetType, String reportNotes,
                               List<String> activeChecklistColumns) {
    }

    /**
     * Returns the id_asset_damage of every unresolved OPEN damage for the asset.
     * Empty list means no damage (skip notification) or query failure.
     */
    public List<Long> findUnresolvedOpenDamageIds(String assetIdentifier) {
        if (assetIdentifier == null || assetIdentifier.isBlank()) {
            return List.of();
        }
        try {
            return jdbc.query(UNRESOLVED_IDS_QUERY,
                    (rs, rowNum) -> rs.getLong("id_asset_damage"),
                    assetIdentifier);
        } catch (Exception e) {
            log.error("findUnresolvedOpenDamageIds query failed for asset_identifier={}: {}",
                    assetIdentifier, e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns the attachments (id_document + filename) of the given damage rows.
     * Rows with id_document IS NULL (failed upload) are excluded.
     */
    public List<DamageAttachment> findAttachmentsByDamageIds(List<Long> damageIds) {
        if (damageIds == null || damageIds.isEmpty()) {
            return List.of();
        }
        String placeholders = damageIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT id_document, filename "
                + "FROM evt_damage_attachment "
                + "WHERE id_asset_damage IN (" + placeholders + ") "
                + "  AND id_document IS NOT NULL "
                + "ORDER BY id_damage_attachment";
        try {
            return jdbc.query(sql,
                    (rs, rowNum) -> new DamageAttachment(rs.getLong("id_document"), rs.getString("filename")),
                    damageIds.toArray());
        } catch (Exception e) {
            log.error("findAttachmentsByDamageIds query failed for damageIds={}: {}",
                    damageIds, e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns, per damage id, the asset type, the opening notes (report_notes) and the list of
     * active checklist columns (boolean dmg_* columns set to true in the asset-type-specific
     * label table). Used to compose the notification text.
     */
    public List<DamageDetail> findDamageDetails(List<Long> damageIds) {
        if (damageIds == null || damageIds.isEmpty()) {
            return List.of();
        }
        String placeholders = damageIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT id_asset_damage, asset_type, report_notes "
                + "FROM evt_asset_damages "
                + "WHERE id_asset_damage IN (" + placeholders + ") "
                + "ORDER BY id_asset_damage";
        try {
            List<DamageDetail> base = jdbc.query(sql,
                    (rs, rowNum) -> new DamageDetail(
                            rs.getLong("id_asset_damage"),
                            rs.getString("asset_type"),
                            rs.getString("report_notes"),
                            List.of()),
                    damageIds.toArray());

            List<DamageDetail> out = new ArrayList<>(base.size());
            for (DamageDetail d : base) {
                List<String> cols = findActiveChecklistColumns(d.idAssetDamage(), d.assetType());
                out.add(new DamageDetail(d.idAssetDamage(), d.assetType(), d.reportNotes(), cols));
            }
            return out;
        } catch (Exception e) {
            log.error("findDamageDetails query failed for damageIds={}: {}", damageIds, e.getMessage());
            return List.of();
        }
    }

    /** Reads the active (true) dmg_* boolean columns from the asset-type-specific label table. */
    private List<String> findActiveChecklistColumns(Long idAssetDamage, String assetType) {
        String table = "UNIT".equalsIgnoreCase(assetType)
                ? "evt_unit_damage_labels"
                : "evt_vehicle_damage_labels";
        String sql = "SELECT * FROM " + table + " WHERE id_asset_damage = ?";
        try {
            return jdbc.query(sql, rs -> {
                List<String> active = new ArrayList<>();
                if (rs.next()) {
                    ResultSetMetaData md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String col = md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                        if (col.startsWith("dmg_")) {
                            Object v = rs.getObject(i);
                            if (v instanceof Boolean b && b) {
                                active.add(col);
                            }
                        }
                    }
                }
                return active;
            }, idAssetDamage);
        } catch (Exception e) {
            log.warn("findActiveChecklistColumns failed for id_asset_damage={}, assetType={}: {}",
                    idAssetDamage, assetType, e.getMessage());
            return List.of();
        }
    }
}
