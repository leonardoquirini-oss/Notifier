-- Reference DDL for evt_unit_last_position (table already exists in DB)
-- This file is for documentation purposes only.

CREATE TABLE IF NOT EXISTS evt_unit_last_position (
    unit_number      VARCHAR(100) NOT NULL PRIMARY KEY,
    unit_type_code   VARCHAR(50),
    message_type     VARCHAR(100),
    id_unit_event    BIGINT,
    event_time       TIMESTAMP,
    latitude         NUMERIC,
    longitude        NUMERIC,
    container_number VARCHAR(50),
    terminal_code    VARCHAR(100),
    full_empty       VARCHAR(10),
    operator_code    VARCHAR(100),
    event_type       VARCHAR(50),
    updated_at       TIMESTAMP
);
