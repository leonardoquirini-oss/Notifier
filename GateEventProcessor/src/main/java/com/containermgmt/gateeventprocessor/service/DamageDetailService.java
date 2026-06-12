package com.containermgmt.gateeventprocessor.service;

import com.containermgmt.gateeventprocessor.config.DamageLabelsProperties;
import com.containermgmt.gateeventprocessor.repository.AssetDamageRepository;
import com.containermgmt.gateeventprocessor.repository.AssetDamageRepository.DamageDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the human-readable damage sections (checklist labels + opening notes) used in the
 * notification text, resolving checklist columns (dmg_*) to Italian labels via damage-labels config.
 */
@Service
@Slf4j
public class DamageDetailService {

    private final AssetDamageRepository assetDamageRepository;
    private final DamageLabelsProperties damageLabels;

    public DamageDetailService(AssetDamageRepository assetDamageRepository,
                               DamageLabelsProperties damageLabels) {
        this.assetDamageRepository = assetDamageRepository;
        this.damageLabels = damageLabels;
    }

    /** Resolves each damage id into a notification section (checklist labels + report notes). */
    public List<WhatsAppNotifier.DamageInfo> buildDamageInfos(List<Long> damageIds) {
        List<DamageDetail> details = assetDamageRepository.findDamageDetails(damageIds);
        List<WhatsAppNotifier.DamageInfo> out = new ArrayList<>(details.size());
        for (DamageDetail d : details) {
            Map<String, String> labelMap = damageLabels.forAssetType(d.assetType());
            List<String> checklist = new ArrayList<>();
            for (String col : d.activeChecklistColumns()) {
                String label = labelMap.get(col);
                checklist.add(label != null ? label : col);
            }
            out.add(new WhatsAppNotifier.DamageInfo(checklist, d.reportNotes()));
        }
        return out;
    }
}
