-- One-shot: riallinea evt_unit_last_position da evt_unit_events + evt_unit_positions
-- (solo righe con unit_number, latitude e longitude valorizzati, come LastPositionUpserter).
-- Eseguire con l'ingester FERMO.
-- Vincoli NOT NULL della tabella invariati. Le righe derivate da evt_unit_positions
-- usano event_type = 'POSITION' e id_unit_event = evt_unit_positions.id_unit_position
-- (come l'ingester live).

BEGIN;

DELETE FROM evt_unit_last_position;

WITH src AS (
    SELECT unit_number, unit_type_code, message_type, id_unit_event, event_time,
           latitude, longitude, container_number, id_trailer, id_vehicle,
           payload->>'terminalCode' AS terminal_code,
           payload->>'fullEmpty'    AS full_empty,
           payload->>'operatorCode' AS operator_code,
           type                     AS event_type,
           NULLIF(payload->>'eta','')::timestamptz::timestamp AS eta,  -- verifica formato/timezone
           message_id, 1 AS prio
    FROM evt_unit_events
    WHERE unit_number IS NOT NULL AND latitude IS NOT NULL AND longitude IS NOT NULL
    UNION ALL
    SELECT unit_number, unit_type_code, message_type, id_unit_position, position_time,
           latitude, longitude, container_number, id_trailer, id_vehicle,
           NULL, NULL, NULL, 'POSITION', NULL, message_id, 2
    FROM evt_unit_positions
    WHERE unit_number IS NOT NULL AND latitude IS NOT NULL AND longitude IS NOT NULL
),
latest AS (
    SELECT DISTINCT ON (unit_number) *
    FROM src
    ORDER BY unit_number, event_time DESC, prio
)
INSERT INTO evt_unit_last_position
    (unit_number, unit_type_code, message_type, id_unit_event, event_time, latitude, longitude,
     container_number, id_trailer, id_vehicle, terminal_code, full_empty, operator_code,
     event_type, eta, message_id, updated_at)
SELECT unit_number, unit_type_code, message_type, id_unit_event, event_time, latitude, longitude,
       container_number, id_trailer, id_vehicle, terminal_code, full_empty, operator_code,
       event_type, eta, message_id, now()
FROM latest;

-- Verifica prima del COMMIT
SELECT count(*) AS righe, count(DISTINCT unit_number) AS unit,
       count(*) FILTER (WHERE latitude IS NULL OR longitude IS NULL) AS senza_coordinate,
       count(*) FILTER (WHERE event_type = 'POSITION') AS da_posizioni
FROM evt_unit_last_position;

-- COMMIT;   -- oppure ROLLBACK;
