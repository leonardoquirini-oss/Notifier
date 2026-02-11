package com.containermgmt.tfpeventingester.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.IdGenerator;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.Table;

@Table("evt_asset_damages")
@IdName("id_asset_damage")
@IdGenerator("nextval('s_evt_asset_damages')")
public class EvtAssetDamage extends Model {

    public static boolean existsByMessageId(String messageId) {
        return count("message_id = ?", messageId) > 0;
    }

    public static int deleteByMessageId(String messageId) {
        return delete("message_id = ?", messageId);
    }

    public static EvtAssetDamage findByMessageId(String messageId) {
        return findFirst("message_id = ?", messageId);
    }
}
