package com.containermgmt.tfpeventprocessor.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;
import org.javalite.activejdbc.annotations.IdName;
import org.javalite.activejdbc.annotations.IdGenerator;

/**
 * Event Model - ActiveJDBC
 *
 * Maps to the 'events' table in the database.
 * Update @Table annotation when actual table name is provided.
 *
 * ActiveJDBC uses dynamic attributes - no need to declare fields explicitly.
 */
@Table("evt_raw_events_OLD")
@IdName("id_event")
@IdGenerator("nextval('s_evt_raw_events')")
public class RawEventOLD extends Model {

    // Helper methods for common fields

    public String getEventId() {
        return getString("event_id");
    }

    public void setEventId(String eventId) {
        set("event_id", eventId);
    }

    public String getEventType() {
        return getString("event_type");
    }

    public void setEventType(String eventType) {
        set("event_type", eventType);
    }

    public String getSource() {
        return getString("source");
    }

    public void setSource(String source) {
        set("source", source);
    }

    public String getPayload() {
        return getString("payload");
    }

    public void setPayload(String payload) {
        set("payload", payload);
    }

    public String getMetadata() {
        return getString("metadata");
    }

    public void setMetadata(String metadata) {
        set("metadata", metadata);
    }

    // Static finder methods

    public static RawEventOLD findByEventId(String eventId) {
        return RawEventOLD.findFirst("event_id = ?", eventId);
    }

    // Validation (optional - uncomment if needed)
    // static {
    //     validatePresenceOf("event_id", "event_type");
    // }

}
