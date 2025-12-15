CREATE SEQUENCE IF NOT EXISTS s_evt_raw_events START 1000;

CREATE TABLE IF NOT EXISTS evt_raw_events (
    id_event integer PRIMARY KEY,
    event_id VARCHAR(255) UNIQUE NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    source VARCHAR(255),
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    payload JSONB NOT NULL,
    metadata JSONB,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_events_event_type ON evt_raw_events(event_type);
CREATE INDEX idx_events_timestamp ON evt_raw_events(timestamp);
CREATE INDEX idx_events_processed_at ON evt_raw_events(processed_at);