-- One-shot backfill of evt_unit_last_position from historical data.
--
-- Rebuilds the "current state" table from evt_unit_events + evt_unit_positions:
-- for each unit_number, picks the row with the most recent event_time across
-- BOTH source tables and upserts it. container_number is copied from the winning
-- row; id_trailer / id_vehicle are resolved fresh from the plate against the
-- fleet tables (see the join below). Idempotent — safe to re-run.
--
-- NOTE: no "event_time > stored" guard here. DISTINCT ON already selects the
-- newest row per unit, so we write it unconditionally. The live UPSERT
-- (LastPositionUpserter) keeps the strict guard; this one-shot must not, or
-- rows whose stored event_time already equals the winner would never get the
-- new id_trailer / id_vehicle columns filled.
--
-- Run when convenient. If live ingestion is active it may race, but the state
-- self-heals as new events arrive.

WITH candidates AS (
    SELECT
        unit_number, unit_type_code, message_type,
        id_unit_event,
        event_time, latitude, longitude, container_number,
        id_trailer, id_vehicle,
        payload->>'terminalCode'                  AS terminal_code,
        payload->>'fullEmpty'                     AS full_empty,
        payload->>'operatorCode'                  AS operator_code,
        COALESCE(type, 'UNKNOWN')                 AS event_type,  -- column is NOT NULL
        NULLIF(payload->>'eta', '')::timestamptz  AS eta,
        message_id
    FROM evt_unit_events
    WHERE unit_number IS NOT NULL AND event_time IS NOT NULL

    UNION ALL

    SELECT
        -- Vehicle/trailer positions carry the identity in vehicle_plate, not unit_number.
        -- Key on the plate so id_trailer/id_vehicle land in last_position (PK unit_number).
        COALESCE(unit_number, vehicle_plate)      AS unit_number,
        unit_type_code, message_type,
        id_unit_position      AS id_unit_event,  -- positions have no parent event; use own PK
        position_time         AS event_time,
        latitude, longitude, container_number,
        id_trailer, id_vehicle,
        NULL, NULL, NULL,     -- terminal_code, full_empty, operator_code
        'POSITION'            AS event_type,   -- positions have no event_type; column is NOT NULL
        NULL::timestamptz     AS eta,
        message_id
    FROM evt_unit_positions
    WHERE COALESCE(unit_number, vehicle_plate) IS NOT NULL AND position_time IS NOT NULL
),
winners AS (
    SELECT DISTINCT ON (unit_number) *
    FROM candidates
    ORDER BY unit_number, event_time DESC
)
INSERT INTO evt_unit_last_position (
    unit_number, unit_type_code, message_type, id_unit_event,
    event_time, latitude, longitude, container_number,
    id_trailer, id_vehicle,
    terminal_code, full_empty, operator_code, event_type, eta, message_id, updated_at
)
-- id_trailer / id_vehicle are resolved from the unit_number (= plate for
-- trailer/vehicle units) against the fleet tables, mirroring the live BERLink
-- lookup cascade (trailers search-by-plate / vehicles by-plate). Plate match is
-- case/space-insensitive. This is independent of which source row won, so a unit
-- gets its trailer/vehicle id even when the newest event carried a null one.
SELECT
    w.unit_number, w.unit_type_code, w.message_type, w.id_unit_event,
    w.event_time, w.latitude, w.longitude, w.container_number,
    t.id_trailer, v.id_vehicle,
    w.terminal_code, w.full_empty, w.operator_code, w.event_type, w.eta, w.message_id, now()
FROM winners w
LEFT JOIN flt_trailers t
       ON replace(upper(t.plate_number), ' ', '') = replace(upper(w.unit_number), ' ', '')
LEFT JOIN flt_vehicles v
       ON replace(upper(v.plate_number), ' ', '') = replace(upper(w.unit_number), ' ', '')
ON CONFLICT (unit_number) DO UPDATE SET
    unit_type_code   = EXCLUDED.unit_type_code,
    message_type     = EXCLUDED.message_type,
    id_unit_event    = EXCLUDED.id_unit_event,
    event_time       = EXCLUDED.event_time,
    latitude         = EXCLUDED.latitude,
    longitude        = EXCLUDED.longitude,
    container_number = EXCLUDED.container_number,
    id_trailer       = EXCLUDED.id_trailer,
    id_vehicle       = EXCLUDED.id_vehicle,
    terminal_code    = EXCLUDED.terminal_code,
    full_empty       = EXCLUDED.full_empty,
    operator_code    = EXCLUDED.operator_code,
    event_type       = EXCLUDED.event_type,
    eta              = EXCLUDED.eta,
    message_id       = EXCLUDED.message_id,
    updated_at       = now();
