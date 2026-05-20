package com.containermgmt.gateeventprocessor.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads from BERLink evt_asset_damages.
 */
@Repository
@Slf4j
public class AssetDamageRepository {

    /** One attachment of an open damage: id_document + filename (from evt_damage_attachment). */
    public record DamageAttachment(Long idDocument, String filename) {}

    /**
     * Attachments (evt_damage_attachment) linked to the unresolved OPEN damages of the asset.
     * Same OPEN/unresolved filter as {@link #hasUnresolvedOpenDamage(String)}, joined to the
     * attachment table; only rows with id_document IS NOT NULL are returned.
     */
    private static final String ATTACHMENTS_QUERY = """
            SELECT a.id_document, a.filename
            FROM evt_asset_damages d
            JOIN evt_damage_attachment a ON a.id_asset_damage = d.id_asset_damage
            WHERE d.asset_identifier = ?
              AND d.status = 'OPEN'
              AND a.id_document IS NOT NULL
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

    /**
     * Returns true if there is at least one OPEN damage row for the given asset_identifier
     * whose tfp_event_id has no matching REPAIRED / UNDER_REPAIR row.
     *
     * OPEN rows with tfp_event_id IS NULL are treated as orphan (no closure possible),
     * therefore they also trigger the notification.
     */
    private static final String QUERY = """
            SELECT 1
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
            LIMIT 1
            """;

    private final JdbcTemplate jdbc;

    public AssetDamageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasUnresolvedOpenDamage(String assetIdentifier) {
        if (assetIdentifier == null || assetIdentifier.isBlank()) {
            return false;
        }
        try {
            Integer found = jdbc.query(QUERY,
                    rs -> rs.next() ? rs.getInt(1) : null,
                    assetIdentifier);
            return found != null;
        } catch (Exception e) {
            log.error("hasUnresolvedOpenDamage query failed for asset_identifier={}: {}",
                    assetIdentifier, e.getMessage());
            return false;
        }
    }

    public List<DamageAttachment> findOpenDamageAttachments(String assetIdentifier) {
        if (assetIdentifier == null || assetIdentifier.isBlank()) {
            return List.of();
        }
        try {
            return jdbc.query(ATTACHMENTS_QUERY,
                    (rs, rowNum) -> new DamageAttachment(
                            rs.getLong("id_document"),
                            rs.getString("filename")),
                    assetIdentifier);
        } catch (Exception e) {
            log.error("findOpenDamageAttachments query failed for asset_identifier={}: {}",
                    assetIdentifier, e.getMessage());
            return List.of();
        }
    }
}
