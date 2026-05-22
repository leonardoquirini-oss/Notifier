package com.containermgmt.tfpeventingester.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Risolve la "mission" (transportOrder.referenceNumber) di un unit event
 * interrogando l'endpoint TFP missions/browse.
 *
 * <p>Una missione e' considerata valida solo se l'event_time ricade
 * nell'intervallo [startTime, endTime] della prima missione trovata.
 * endTime null/vuoto e' trattato come 2100-01-01 (intervallo aperto).
 */
@Component
@Slf4j
public class TfpMissionLookupService {

    private static final String BROWSE_ENDPOINT = "/api/missions-tracking/missions/browse";
    private static final Instant OPEN_END = Instant.parse("2100-01-01T00:00:00Z");
    private static final DateTimeFormatter ISO_MILLIS_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final TfpClient tfpClient;

    public TfpMissionLookupService(TfpClient tfpClient) {
        this.tfpClient = tfpClient;
    }

    /**
     * @param unitNumber   evt_unit_events.unit_number
     * @param unitTypeCode evt_unit_events.unit_type_code
     * @param eventTime    evt_unit_events.event_time
     * @return referenceNumber della missione, o null se non trovata / fuori intervallo / errore
     */
    @SuppressWarnings("unchecked")
    public String lookupMission(String unitNumber, String unitTypeCode, Instant eventTime) {
        if (!tfpClient.isConfigured() || eventTime == null) {
            return null;
        }

        String unitNumberParam = isContainerOrNull(unitTypeCode) ? safe(unitNumber) : "";
        String trailerPlateParam = "SEMITRAILER".equals(unitTypeCode) ? safe(unitNumber) : "";

        if (unitNumberParam.isEmpty() && trailerPlateParam.isEmpty()) {
            return null;
        }

        try {
            Map<String, Object> filter = new java.util.LinkedHashMap<>();
            filter.put("unitNumber", unitNumberParam);
            filter.put("trailerPlate", trailerPlateParam);
            filter.put("overIntervalFrom", ISO_MILLIS_UTC.format(eventTime.truncatedTo(ChronoUnit.SECONDS)));

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("offset", 0);
            body.put("limit", 1);
            body.put("filter", filter);
            body.put("sortingList", List.of(Map.of("column", "startTime", "direction", "ASC")));

            Map<String, Object> response = tfpClient.post(BROWSE_ENDPOINT, body, Map.class);
            return extractMission(response, eventTime);
        } catch (Exception e) {
            log.warn("TFP mission lookup fallito per unitNumber={}, trailerPlate={}: {}",
                    unitNumberParam, trailerPlateParam, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractMission(Map<String, Object> response, Instant eventTime) {
        if (response == null) {
            return null;
        }
        Object resultListObj = response.get("resultList");
        if (!(resultListObj instanceof List<?> resultList) || resultList.isEmpty()) {
            return null;
        }
        Map<String, Object> mission = (Map<String, Object>) resultList.get(0);

        Instant start = parseInstant(mission.get("startTime"));
        Instant end = parseInstant(mission.get("endTime"));
        if (end == null) {
            end = OPEN_END;
        }
        if (start == null || eventTime.isBefore(start) || eventTime.isAfter(end)) {
            log.debug("Mission fuori intervallo: eventTime={}, start={}, end={}", eventTime, start, end);
            return null;
        }

        // Prima transportOrder.referenceNumber, poi fallback su transportOrderShortCode
        if (mission.get("transportOrder") instanceof Map<?, ?> to && to.get("referenceNumber") != null) {
            return to.get("referenceNumber").toString();
        }
        Object shortCode = mission.get("transportOrderShortCode");
        return shortCode != null ? shortCode.toString() : null;
    }

    private boolean isContainerOrNull(String unitTypeCode) {
        return unitTypeCode == null || "CONTAINER".equals(unitTypeCode);
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private Instant parseInstant(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.toString());
        } catch (Exception e) {
            log.warn("Impossibile parsare timestamp missione: {}", value);
            return null;
        }
    }
}
