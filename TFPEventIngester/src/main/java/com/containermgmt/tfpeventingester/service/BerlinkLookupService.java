package com.containermgmt.tfpeventingester.service;

import com.containermgmt.tfpeventingester.config.BerlinkApiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class BerlinkLookupService {

    private final RestTemplate restTemplate;
    private final BerlinkApiConfig config;

    public BerlinkLookupService(RestTemplate berlinkRestTemplate, BerlinkApiConfig config) {
        this.restTemplate = berlinkRestTemplate;
        this.config = config;
    }

    public LookupResult lookupUnit(String unitNumber, String unitTypeCode) {
        if (unitNumber == null || unitNumber.isBlank()) {
            return LookupResult.empty();
        }

        try {
            if ("CONTAINER".equalsIgnoreCase(unitTypeCode)) {
                return lookupContainer(unitNumber);
            } else {
                return lookupNonContainer(unitNumber);
            }
        } catch (Exception e) {
            log.warn("BERLink lookup failed for unitNumber={}, unitTypeCode={}: {}",
                    unitNumber, unitTypeCode, e.getMessage());
            return LookupResult.empty();
        }
    }

    String formatContainerNumberForSearch(String unitNumber) {
        if (unitNumber == null || unitNumber.isBlank()) {
            return unitNumber;
        }

        String formatted;
        if (unitNumber.startsWith("GBTU")) {
            formatted = formatGbtuNumber(unitNumber);
        } else if (unitNumber.startsWith("BRND")) {
            formatted = formatBrndNumber(unitNumber);
        } else {
            return unitNumber;
        }

        log.debug("Formatted search number: {} → {}", unitNumber, formatted);
        return formatted;
    }

    private String formatGbtuNumber(String unitNumber) {
        String digits = unitNumber.substring(4);
        if (digits.length() < 2 || !digits.chars().allMatch(Character::isDigit)) {
            return unitNumber;
        }
        String stripped = digits.replaceFirst("^0+", "");
        if (stripped.length() < 2) {
            stripped = digits.substring(digits.length() - 2);
        }
        return "GBTU*" + stripped.substring(0, stripped.length() - 1) + "." + stripped.charAt(stripped.length() - 1);
    }

    private String formatBrndNumber(String unitNumber) {
        String digits = unitNumber.substring(4);
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return unitNumber;
        }
        String stripped = digits.replaceFirst("^0+", "");
        return "BRND*" + (stripped.isEmpty() ? "0" : stripped);
    }

    private LookupResult lookupContainer(String unitNumber) {
        String searchNumber = formatContainerNumberForSearch(unitNumber);
        List<Map<String, Object>> results = searchUnits(searchNumber, false);

        if (results == null || results.isEmpty()) {
            log.debug("No container found for unitNumber={}", unitNumber);
            return LookupResult.empty();
        }

        Map<String, Object> first = results.get(0);
        String unitType = getStringValue(first, "unitType");
        if ("c".equals(unitType)) {
            String cassa = getStringValue(first, "cassa");
            log.debug("Container lookup: unitNumber={} → containerNumber={}", unitNumber, cassa);
            return LookupResult.ofContainer(cassa);
        }

        log.debug("Unit found but unitType={} (expected 'c') for unitNumber={}", unitType, unitNumber);
        return LookupResult.empty();
    }

    private LookupResult lookupNonContainer(String unitNumber) {
        List<Map<String, Object>> results = searchUnits(unitNumber, true);
        if (results != null && !results.isEmpty()) {
            Map<String, Object> first = results.get(0);
            String unitType = getStringValue(first, "unitType");
            Integer id = getIntegerValue(first, "id");

            if ("t".equals(unitType) && id != null) {
                log.debug("Trailer lookup: unitNumber={} → idTrailer={}", unitNumber, id);
                return LookupResult.ofTrailer(id);
            }
            if ("v".equals(unitType) && id != null) {
                log.debug("Vehicle lookup via units/search: unitNumber={} → idVehicle={}", unitNumber, id);
                return LookupResult.ofVehicle(id);
            }
        }

        // Fallback: search by plate
        return lookupVehicleByPlate(unitNumber);
    }

    private List<Map<String, Object>> searchUnits(String unitNumber, boolean includeVehicles) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/api/units/search")
                .queryParam("q", unitNumber)
                .queryParam("limit", 1);

        if (includeVehicles) {
            builder.queryParam("includeVehicles", true);
        }

        String url = builder.toUriString();
        log.debug("BERLink units search: {}", url);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                url, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private LookupResult lookupVehicleByPlate(String plateNumber) {
        String url = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/api/vehicles/by-plate/{plateNumber}")
                .buildAndExpand(plateNumber)
                .toUriString();
        log.debug("BERLink vehicle by plate: {}", url);

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<>() {});

            Map<String, Object> body = response.getBody();
            if (body == null) {
                return LookupResult.empty();
            }

            String status = getStringValue(body, "status");
            if (!"success".equals(status)) {
                return LookupResult.empty();
            }

            Object data = body.get("data");
            if (data instanceof Map) {
                Integer idVehicle = getIntegerValue((Map<String, Object>) data, "id_vehicle");
                if (idVehicle != null) {
                    log.debug("Vehicle by plate: plate={} → idVehicle={}", plateNumber, idVehicle);
                    return LookupResult.ofVehicle(idVehicle);
                }
            }
        } catch (Exception e) {
            log.warn("Vehicle by plate lookup failed for plate={}: {}", plateNumber, e.getMessage());
        }

        return LookupResult.empty();
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Integer getIntegerValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record LookupResult(String containerNumber, Integer idTrailer, Integer idVehicle) {
        public static LookupResult empty() {
            return new LookupResult(null, null, null);
        }

        public static LookupResult ofContainer(String containerNumber) {
            return new LookupResult(containerNumber, null, null);
        }

        public static LookupResult ofTrailer(Integer idTrailer) {
            return new LookupResult(null, idTrailer, null);
        }

        public static LookupResult ofVehicle(Integer idVehicle) {
            return new LookupResult(null, null, idVehicle);
        }

        public boolean hasData() {
            return containerNumber != null || idTrailer != null || idVehicle != null;
        }
    }
}
