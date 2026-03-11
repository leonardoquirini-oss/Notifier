-- Reference DDL for evt_unit_events (table already exists in DB, partitioned by event_time)
-- This file is for documentation purposes only.

-- Sequence for evt_unit_events primary key
CREATE SEQUENCE IF NOT EXISTS s_evt_unit_events START WITH 1 INCREMENT BY 1;

-- Table: evt_unit_events (partitioned by RANGE on event_time)
CREATE TABLE IF NOT EXISTS evt_unit_events (
    id_unit_event    BIGINT DEFAULT nextval('s_evt_unit_events'),
    message_id       VARCHAR(255) NOT NULL,
    message_type     VARCHAR(100),
    id               VARCHAR(255),
    type             VARCHAR(100),
    create_time      TIMESTAMP,
    event_time       TIMESTAMP NOT NULL,
    latitude         NUMERIC,
    longitude        NUMERIC,
    unit_number      VARCHAR(100),
    unit_type_code   VARCHAR(50),
    id_trailer       INTEGER,
    id_vehicle       INTEGER,
    container_number VARCHAR(50),
    payload          JSONB
) PARTITION BY RANGE (event_time);

-- Index on message_id for deduplication lookups
CREATE UNIQUE INDEX IF NOT EXISTS idx_evt_unit_events_message_id ON evt_unit_events (message_id, event_time);
