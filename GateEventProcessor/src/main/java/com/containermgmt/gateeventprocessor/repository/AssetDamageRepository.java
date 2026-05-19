package com.containermgmt.gateeventprocessor.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads from BERLink evt_asset_damages.
 */
@Repository
@Slf4j
public class AssetDamageRepository {

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
}
