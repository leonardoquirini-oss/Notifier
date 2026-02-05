package com.containermgmt.tfpeventingester.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.IdGenerator;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.Table;

@Table("evt_unit_events")
@IdName("id_unit_event")
@IdGenerator("nextval('s_evt_unit_events')")
public class EvtUnitEvent extends Model {

    public static boolean existsByMessageId(String messageId) {
        return count("message_id = ?", messageId) > 0;
    }

    public static int deleteByMessageId(String messageId) {
        return delete("message_id = ?", messageId);
    }

}
