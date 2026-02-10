package com.containermgmt.tfpeventingester.stream;

import com.containermgmt.tfpeventingester.model.EvtAssetDamage;
import com.containermgmt.tfpeventingester.model.EvtUnitDamageLabel;
import com.containermgmt.tfpeventingester.model.EvtVehicleDamageLabel;
import com.containermgmt.tfpeventingester.service.BerlinkLookupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Processes messages from tfp-asset-damages-stream and persists them to
 * evt_asset_damages + evt_vehicle_damage_labels / evt_unit_damage_labels.
 */
@Component
@Slf4j
public class AssetDamageStreamProcessor extends AbstractStreamProcessor {

    /** Maps JSON tag → DB column for VEHICLE labels */
    private static final Map<String, String> VEHICLE_TAG_TO_COLUMN = Map.of(
            "DMG_BRACKING", "dmg_braking",
            "DMG_TYRES", "dmg_tyres",
            "DMG_ISO_AIR", "dmg_iso_air",
            "DMG_LIGHT", "dmg_light",
            "DMG_TWIST_LOCK", "dmg_twist",
            "DMG_OTHER", "dmg_other");

    /** Maps JSON tag → DB column for UNIT labels */
    private static final Map<String, String> UNIT_TAG_TO_COLUMN = Map.of(
            "DMG_STRUCTURE", "dmg_structure",
            "DMG_DOORS", "dmg_doors",
            "DMG_ROOF", "dmg_roof",
            "DMG_WALLS", "dmg_walls",
            "DMG_TWIST_LOCK", "dmg_twist_lock",
            "DMG_OTHER", "dmg_other");

    public AssetDamageStreamProcessor(ObjectMapper objectMapper,
                                       BerlinkLookupService berlinkLookupService,
                                       @Value("${stream.asset-damages.key}") String streamKey,
                                       @Value("${stream.asset-damages.consumer-group}") String consumerGroup) {
        super(objectMapper, berlinkLookupService, streamKey, consumerGroup);
    }

    @Override
    protected Model buildModel(String messageId, String eventType, Map<String, Object> payload) {
        // Not used — buildModels() is overridden instead
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Model> buildModels(String messageId, String eventType, Map<String, Object> payload) {
        Long idAssetDamage = getLong(payload, "id");
        if (idAssetDamage == null) {
            log.warn("Missing 'id' in asset damage payload for message_id={}, skipping", messageId);
            return List.of();
        }

        // Main model
        EvtAssetDamage damage = new EvtAssetDamage();
        damage.setId(idAssetDamage);
        damage.set("message_id", messageId);
        damage.set("type", getString(payload, "type"));
        damage.set("status", getString(payload, "status"));
        damage.set("asset_id", getLong(payload, "assetId"));
        damage.set("edit_time", parseTimestamp(payload, "editTime"));
        damage.set("severity", getString(payload, "severity"));
        damage.set("asset_type", getString(payload, "assetType"));
        damage.set("asset_owner", getString(payload, "assetOwner"));
        damage.set("edit_user_id", getLong(payload, "editUserId"));
        damage.set("report_time", parseTimestamp(payload, "reportTime"));
        damage.set("closing_time", parseTimestamp(payload, "closingTime"));
        damage.set("description", getString(payload, "description"));
        damage.set("report_notes", getString(payload, "reportNotes"));
        damage.set("closing_user_id", getLong(payload, "closingUserId"));
        damage.set("asset_identifier", getString(payload, "assetIdentifier"));

        List<Model> models = new ArrayList<>(2);
        models.add(damage);

        // Label model (depends on assetType)
        String assetType = getString(payload, "assetType");
        List<Map<String, Object>> labels =
                (List<Map<String, Object>>) payload.get("assetDamageLabels");

        if (labels != null && !labels.isEmpty()) {
            Set<String> activeTags = extractActiveTags(labels);

            if ("VEHICLE".equalsIgnoreCase(assetType)) {
                models.add(buildVehicleLabel(idAssetDamage, activeTags));
            } else if ("UNIT".equalsIgnoreCase(assetType)) {
                models.add(buildUnitLabel(idAssetDamage, activeTags));
            }
        }

        return models;
    }

    private Set<String> extractActiveTags(List<Map<String, Object>> labels) {
        Set<String> tags = new HashSet<>();
        for (Map<String, Object> label : labels) {
            Object tag = label.get("assetDamageLabel");
            if (tag != null) {
                tags.add(tag.toString());
            }
        }
        return tags;
    }

    private EvtVehicleDamageLabel buildVehicleLabel(Long idAssetDamage, Set<String> activeTags) {
        EvtVehicleDamageLabel label = new EvtVehicleDamageLabel();
        label.set("id_asset_damage", idAssetDamage);
        for (Map.Entry<String, String> entry : VEHICLE_TAG_TO_COLUMN.entrySet()) {
            label.set(entry.getValue(), activeTags.contains(entry.getKey()));
        }
        return label;
    }

    private EvtUnitDamageLabel buildUnitLabel(Long idAssetDamage, Set<String> activeTags) {
        EvtUnitDamageLabel label = new EvtUnitDamageLabel();
        label.set("id_asset_damage", idAssetDamage);
        for (Map.Entry<String, String> entry : UNIT_TAG_TO_COLUMN.entrySet()) {
            label.set(entry.getValue(), activeTags.contains(entry.getKey()));
        }
        return label;
    }

    // --- BERLink lookup overrides ---

    @Override
    protected String getUnitNumberFromPayload(Map<String, Object> payload) {
        return getString(payload, "assetIdentifier");
    }

    @Override
    protected String getUnitTypeCodeFromPayload(Map<String, Object> payload) {
        String assetType = getString(payload, "assetType");
        // TFP uses "UNIT" for containers, BERLink expects "CONTAINER"
        if ("UNIT".equalsIgnoreCase(assetType)) {
            return "CONTAINER";
        }
        return assetType;
    }

    // --- Dedup & resend ---

    @Override
    protected boolean existsByMessageId(String messageId) {
        return EvtAssetDamage.existsByMessageId(messageId);
    }

    @Override
    protected int deleteByMessageId(String messageId) {
        // Cascade: delete associated labels before the main record
        EvtAssetDamage existing = EvtAssetDamage.findByMessageId(messageId);
        if (existing != null) {
            Long idAssetDamage = (Long) existing.getId();
            EvtVehicleDamageLabel.deleteByAssetDamageId(idAssetDamage);
            EvtUnitDamageLabel.deleteByAssetDamageId(idAssetDamage);
        }
        return EvtAssetDamage.deleteByMessageId(messageId);
    }

    @Override
    protected String processorName() {
        return "asset damage";
    }
}
