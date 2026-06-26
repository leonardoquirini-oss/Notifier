package com.containermgmt.eventcontrolprocessor.model;

import java.time.LocalDateTime;

/**
 * A row of evt_unit_events relevant to control rules.
 * latitude/longitude may be null when the source event carried no position.
 */
public record UnitEvent(
        long idUnitEvent,
        String type,
        String unitNumber,
        LocalDateTime eventTime,
        Double latitude,
        Double longitude
) {
}
