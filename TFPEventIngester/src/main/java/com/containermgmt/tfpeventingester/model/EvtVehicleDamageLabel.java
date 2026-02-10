package com.containermgmt.tfpeventingester.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.IdGenerator;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.Table;

@Table("evt_vehicle_damage_labels")
@IdName("id_vehicle_damage_label")
@IdGenerator("nextval('s_evt_vehicle_damage_labels')")
public class EvtVehicleDamageLabel extends Model {

    public static int deleteByAssetDamageId(Long idAssetDamage) {
        return delete("id_asset_damage = ?", idAssetDamage);
    }
}
