package com.containermgmt.gateeventprocessor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds damage-labels.* — maps a damage checklist DB column (e.g. dmg_tyres) to a
 * human-readable Italian label, split by asset type (vehicle / unit).
 */
@ConfigurationProperties(prefix = "damage-labels")
@Getter
@Setter
public class DamageLabelsProperties {

    /** column -> label IT, for asset_type = VEHICLE (evt_vehicle_damage_labels). */
    private Map<String, String> vehicle = new LinkedHashMap<>();

    /** column -> label IT, for asset_type = UNIT (evt_unit_damage_labels). */
    private Map<String, String> unit = new LinkedHashMap<>();

    /** Returns the label map for the given asset_type (defaults to vehicle map if unknown). */
    public Map<String, String> forAssetType(String assetType) {
        if ("UNIT".equalsIgnoreCase(assetType)) {
            return unit;
        }
        return vehicle;
    }
}
