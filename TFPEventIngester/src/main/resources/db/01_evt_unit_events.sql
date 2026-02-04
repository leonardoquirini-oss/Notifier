-- Sequence for evt_unit_events primary key
CREATE SEQUENCE IF NOT EXISTS s_evt_unit_events START WITH 1 INCREMENT BY 1;

-- Table: evt_unit_events
CREATE TABLE IF NOT EXISTS evt_unit_events (
    id_unit_event   BIGINT DEFAULT nextval('s_evt_unit_events') PRIMARY KEY,
    message_id      VARCHAR(255) NOT NULL,
    message_type    VARCHAR(100),
    id              VARCHAR(255),
    type            VARCHAR(100),
    event_time      TIMESTAMP,
    create_time     TIMESTAMP,
    latitude        NUMERIC,
    longitude       NUMERIC,
    severity        VARCHAR(50),
    unit_number     VARCHAR(100),
    unit_type_code  VARCHAR(50),
    damage_type     VARCHAR(100),
    report_notes    TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index on message_id for deduplication lookups
CREATE UNIQUE INDEX IF NOT EXISTS idx_evt_unit_events_message_id ON evt_unit_events (message_id);
