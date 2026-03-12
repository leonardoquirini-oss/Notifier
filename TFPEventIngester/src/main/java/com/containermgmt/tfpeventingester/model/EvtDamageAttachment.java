package com.containermgmt.tfpeventingester.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.IdGenerator;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.Table;

import java.util.List;
import java.util.stream.Collectors;

@Table("evt_damage_attachment")
@IdName("id_damage_attachment")
@IdGenerator("nextval('s_evt_damage_attachment')")
public class EvtDamageAttachment extends Model {

    public static List<Long> findIdDocumentsByAssetDamageId(Long idAssetDamage) {
        return find("id_asset_damage = ? AND id_document IS NOT NULL", idAssetDamage)
                .stream()
                .map(m -> ((Number) m.get("id_document")).longValue())
                .collect(Collectors.toList());
    }

    public static int deleteByAssetDamageId(Long idAssetDamage) {
        return delete("id_asset_damage = ?", idAssetDamage);
    }
}
