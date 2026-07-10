package com.containermgmt.tfpeventingester.service;

import com.containermgmt.tfpeventingester.config.BerlinkApiConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Lookup di un identifier su BERLink. Esegue cascade su tutti gli endpoint
 * (container search, units search con vehicles, trailer by-plate, vehicle by-plate)
 * accumulando i match. Un identifier puo' risolversi simultaneamente come
 * container + trailer + vehicle (es. silos = container montato su semirimorchio).
 * Il parametro unitTypeCode resta nella signature per back-compat ma e' ignorato
 * dalla logica.
 */
@Component
@Slf4j
public class BerlinkLookupService {

    private static final String CACHE_KEY_PREFIX = "unit:lookup:";

    private final RestTemplate restTemplate;
    private final BerlinkApiConfig config;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public BerlinkLookupService(RestTemplate berlinkRestTemplate, BerlinkApiConfig config,
                                RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.restTemplate = berlinkRestTemplate;
        this.config = config;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public LookupResult lookupUnit(String unitNumber, String unitTypeCode) {
        if (unitNumber == null || unitNumber.isBlank()) {
            return LookupResult.empty();
        }

        String cacheKey = buildCacheKey(unitNumber);

        if (config.isCacheEnabled()) {
            LookupResult cached = readFromCache(cacheKey);
            if (cached != null) {
                log.debug("Cache HIT for key={}", cacheKey);
                return cached;
            }
            log.debug("Cache MISS for key={}", cacheKey);
        }

        LookupResult result = LookupResult.empty();
        result = result.merge(safeContainerLookup(unitNumber));
        result = result.merge(safeUnitsSearchLookup(unitNumber));
        if (result.idTrailer() == null) {
            result = result.merge(safeTrailerByPlateLookup(unitNumber));
        }
        if (result.idVehicle() == null) {
            result = result.merge(safeVehicleByPlateLookup(unitNumber));
        }

        if (config.isCacheEnabled()) {
            long ttl = result.hasData() ? config.getCacheTtlMinutes() : config.getCacheNegativeTtlMinutes();
            writeToCache(cacheKey, result, ttl);
        }

        log.debug("Lookup result for unitNumber={}: container={}, idTrailer={}, idVehicle={}",
                unitNumber, result.containerNumber(), result.idTrailer(), result.idVehicle());
        return result;
    }

    private LookupResult safeContainerLookup(String unitNumber) {
        try {
            return lookupContainer(unitNumber);
        } catch (Exception e) {
            log.warn("BERLink container lookup failed for unitNumber={}: {}", unitNumber, e.getMessage());
            return LookupResult.empty();
        }
    }

    private LookupResult safeUnitsSearchLookup(String unitNumber) {
        try {
            return lookupViaUnitsSearch(unitNumber);
        } catch (Exception e) {
            log.warn("BERLink units search lookup failed for unitNumber={}: {}", unitNumber, e.getMessage());
            return LookupResult.empty();
        }
    }

    private LookupResult safeTrailerByPlateLookup(String unitNumber) {
        try {
            return lookupTrailerByPlate(unitNumber);
        } catch (Exception e) {
            log.warn("BERLink trailer by-plate lookup failed for unitNumber={}: {}", unitNumber, e.getMessage());
            return LookupResult.empty();
        }
    }

    private LookupResult safeVehicleByPlateLookup(String unitNumber) {
        try {
            return lookupVehicleByPlate(unitNumber);
        } catch (Exception e) {
            log.warn("BERLink by-plate lookup failed for unitNumber={}: {}", unitNumber, e.getMessage());
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

    private LookupResult lookupViaUnitsSearch(String unitNumber) {
        List<Map<String, Object>> results = searchUnits(unitNumber, true);
        if (results == null || results.isEmpty()) {
            return LookupResult.empty();
        }
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
        return LookupResult.empty();
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

    private LookupResult lookupTrailerByPlate(String plateNumber) {
        // search-by-plate ritorna 200 con data=null quando la targa non e' di un trailer,
        // a differenza di by-plate che risponde 404 (il miss e' il caso comune).
        String url = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/api/trailers/search-by-plate")
                .queryParam("plate", plateNumber)
                .toUriString();
        log.debug("BERLink trailer by plate: {}", url);

        Integer idTrailer = fetchIdByPlate(url, "id_trailer");
        if (idTrailer != null) {
            log.debug("Trailer by plate: plate={} → idTrailer={}", plateNumber, idTrailer);
            return LookupResult.ofTrailer(idTrailer);
        }
        return LookupResult.empty();
    }

    private LookupResult lookupVehicleByPlate(String plateNumber) {
        String url = UriComponentsBuilder
                .fromHttpUrl(config.getBaseUrl() + "/api/vehicles/by-plate/{plateNumber}")
                .buildAndExpand(plateNumber)
                .toUriString();
        log.debug("BERLink vehicle by plate: {}", url);

        Integer idVehicle = fetchIdByPlate(url, "id_vehicle");
        if (idVehicle != null) {
            log.debug("Vehicle by plate: plate={} → idVehicle={}", plateNumber, idVehicle);
            return LookupResult.ofVehicle(idVehicle);
        }
        return LookupResult.empty();
    }

    /**
     * Chiama un endpoint che risponde con l'involucro ApiResponse
     * ({@code {"success": true, "data": {...}}}) ed estrae {@code data.<idField>}.
     * Ritorna null se la risposta non e' di successo o {@code data} e' assente.
     */
    @SuppressWarnings("unchecked")
    private Integer fetchIdByPlate(String url, String idField) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        Map<String, Object> body = response.getBody();
        if (body == null || !Boolean.TRUE.equals(body.get("success"))) {
            return null;
        }

        Object data = body.get("data");
        if (data instanceof Map) {
            return getIntegerValue((Map<String, Object>) data, idField);
        }
        return null;
    }

    private String buildCacheKey(String unitNumber) {
        return CACHE_KEY_PREFIX + unitNumber.trim().toUpperCase();
    }

    private LookupResult readFromCache(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, LookupResult.class);
        } catch (Exception e) {
            log.warn("Cache read failed for key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private void writeToCache(String key, LookupResult result, long ttlMinutes) {
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(key, json, Duration.ofMinutes(ttlMinutes));
            log.debug("Cache write for key={}, ttl={}min", key, ttlMinutes);
        } catch (Exception e) {
            log.warn("Cache write failed for key={}: {}", key, e.getMessage());
        }
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

        public LookupResult merge(LookupResult other) {
            if (other == null) return this;
            return new LookupResult(
                    this.containerNumber != null ? this.containerNumber : other.containerNumber,
                    this.idTrailer != null ? this.idTrailer : other.idTrailer,
                    this.idVehicle != null ? this.idVehicle : other.idVehicle);
        }

        public boolean hasData() {
            return containerNumber != null || idTrailer != null || idVehicle != null;
        }
    }
}
