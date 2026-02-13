CREATE SEQUENCE IF NOT EXISTS s_evt_raw_events START 1000;

CREATE TABLE IF NOT EXISTS evt_raw_events (
    id_event integer PRIMARY KEY ,
    message_id VARCHAR(250),
    event_type VARCHAR(100),
    event_time TIMESTAMP WITH TIME ZONE,
    payload JSONB NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE evt_raw_events ADD CONSTRAINT uq_evt_raw_events_message_id UNIQUE (message_id);

CREATE INDEX IF NOT EXISTS idx_events_message_id ON evt_raw_events(message_id);
CREATE INDEX IF NOT EXISTS idx_events_event_type ON evt_raw_events(event_type);
CREATE INDEX IF NOT EXISTS idx_events_event_time ON evt_raw_events(event_time);
CREATE INDEX IF NOT EXISTS idx_events_processed_at ON evt_raw_events(processed_at);

ALTER TABLE evt_raw_events ADD COLUMN IF NOT EXISTS checksum VARCHAR(100);