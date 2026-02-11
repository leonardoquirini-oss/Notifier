-- Reference DDL for evt_error_ingestion (table already exists in DB)
CREATE SEQUENCE IF NOT EXISTS s_evt_error_ingestion START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS evt_error_ingestion (
    id_error_ingestion BIGINT DEFAULT nextval('s_evt_error_ingestion') PRIMARY KEY,
    message_id         VARCHAR(255) NOT NULL,
    ingestion_time     TIMESTAMP    NOT NULL,
    error_message      TEXT
);

CREATE INDEX IF NOT EXISTS idx_evt_error_ingestion_message_id ON evt_error_ingestion (message_id);
