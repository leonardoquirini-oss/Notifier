package com.containermgmt.tfpeventingester.stream;

import org.javalite.activejdbc.Base;

/**
 * Shared UPSERT logic for the evt_unit_last_position table.
 * <p>
 * One row per unit_number (PK). The row is updated only when the incoming
 * event_time is strictly newer than the stored one, so the table always holds
 * the most recent known position of each unit. Fed by both
 * {@link UnitEventStreamProcessor} (tfp-unit-events-stream) and
 * {@link UnitPositionStreamProcessor} (tfp-unit-positions-stream).
 * <p>
 * Must be invoked inside an open ActiveJDBC transaction/connection.
 */
final class LastPositionUpserter {

    private LastPositionUpserter() {
    }

    private static final String SQL = """
            INSERT INTO evt_unit_last_position (
              unit_number, unit_type_code, message_type, id_unit_event,
              event_time, latitude, longitude, container_number,
              terminal_code, full_empty, operator_code, event_type, eta, message_id, updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
            ON CONFLICT (unit_number) DO UPDATE SET
              message_type=EXCLUDED.message_type, id_unit_event=EXCLUDED.id_unit_event,
              event_time=EXCLUDED.event_time, latitude=EXCLUDED.latitude,
              longitude=EXCLUDED.longitude, container_number=EXCLUDED.container_number,
              terminal_code=EXCLUDED.terminal_code, full_empty=EXCLUDED.full_empty,
              operator_code=EXCLUDED.operator_code, event_type=EXCLUDED.event_type,
              eta=EXCLUDED.eta, message_id=EXCLUDED.message_id, updated_at=now()
            WHERE EXCLUDED.event_time > evt_unit_last_position.event_time
            """;

    /**
     * Upsert the last known position of a unit. No-op when unitNumber is null
     * (unit_number is the PK and cannot be null).
     */
    static void upsert(Object unitNumber, Object unitTypeCode, Object messageType,
                       Object idUnitEvent, Object eventTime, Object latitude, Object longitude,
                       Object containerNumber, Object terminalCode, Object fullEmpty,
                       Object operatorCode, Object eventType, Object eta, Object messageId) {
        if (unitNumber == null) {
            return;
        }
        Base.exec(SQL,
                unitNumber, unitTypeCode, messageType, idUnitEvent, eventTime,
                latitude, longitude, containerNumber, terminalCode, fullEmpty,
                operatorCode, eventType, eta, messageId);
    }
}
