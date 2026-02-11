package com.containermgmt.tfpeventingester.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.IdGenerator;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.Table;

@Table("evt_error_ingestion")
@IdName("id_error_ingestion")
@IdGenerator("nextval('s_evt_error_ingestion')")
public class EvtErrorIngestion extends Model {

    public static int deleteByMessageId(String messageId) {
        return delete("message_id = ?", messageId);
    }
}
