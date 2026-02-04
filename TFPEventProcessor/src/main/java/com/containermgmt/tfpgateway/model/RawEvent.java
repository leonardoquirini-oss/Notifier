package com.containermgmt.tfpgateway.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.IdGenerator;

/**
 * RawEvent Model - ActiveJDBC
 *
 * Maps to the 'evt_raw_events' table in the database.
 * Stores the full raw JSON payload along with extracted key fields for indexing.
 *
 * Columns: id_event, event_type, event_time, payload (JSONB), processed_at, created_at
 */
@Table("evt_raw_events")
@IdName("id_event")
@IdGenerator("nextval('s_evt_raw_events')")
public class RawEvent extends Model {

    public String getEventType() {
        return getString("event_type");
    }

    public String getPayload() {
        return getString("payload");
    }
}
