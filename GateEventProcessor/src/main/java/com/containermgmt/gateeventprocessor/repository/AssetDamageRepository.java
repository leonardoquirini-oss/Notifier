package com.containermgmt.gateeventprocessor.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
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
}
