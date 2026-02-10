-- Reference DDL for evt_unit_positions (table already exists in DB, partitioned by position_time)
-- This file is for documentation purposes only.

-- Sequence for evt_unit_positions primary key
CREATE SEQUENCE IF NOT EXISTS s_evt_unit_positions START WITH 1 INCREMENT BY 1;

-- Table: evt_unit_positions (partitioned by RANGE on position_time)
CREATE TABLE IF NOT EXISTS evt_unit_positions (
    id_unit_position BIGINT DEFAULT nextval('s_evt_unit_positions'),
    message_id       VARCHAR(255) NOT NULL,
    pos_index        SMALLINT NOT NULL DEFAULT 1,
    message_type     VARCHAR(100),
    unit_number      VARCHAR(100),
    unit_type_code   VARCHAR(50),
    vehicle_plate    VARCHAR(50),
    unique_unit      BOOLEAN DEFAULT false,
    unique_vehicle   BOOLEAN DEFAULT false,
    id_trailer       INTEGER,
    id_vehicle       INTEGER,
    container_number VARCHAR(50),
    position_time    TIMESTAMP NOT NULL,
    create_time      TIMESTAMP,
    latitude         NUMERIC,
    longitude        NUMERIC
) PARTITION BY RANGE (position_time);

-- Index on message_id + pos_index for deduplication lookups
CREATE UNIQUE INDEX IF NOT EXISTS idx_evt_unit_positions_message_id ON evt_unit_positions (message_id, pos_index, position_time);
