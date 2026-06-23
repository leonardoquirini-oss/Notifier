package com.containermgmt.tfpeventingester.controller;

import com.containermgmt.tfpeventingester.service.GeofencingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * UI + REST API per la gestione del geofencing (mappe e punti con raggio).
 * Persistenza su SQLite via {@link GeofencingService}.
 */
@Controller
public class GeofencingController {

    private final GeofencingService service;

    public GeofencingController(GeofencingService service) {
        this.service = service;
    }

    /** Pagina Geofencing (shell; i dati sono caricati via fetch). */
    @GetMapping("/geofencing")
    public String page() {
        return "geofencing";
    }

    // ---------------- Maps API ----------------

    @GetMapping("/geofencing/api/maps")
    @ResponseBody
    public List<Map<String, Object>> listMaps() {
        return service.listMaps();
    }

    @PostMapping("/geofencing/api/maps")
    @ResponseBody
    public Map<String, Object> createMap(@RequestBody Map<String, Object> body) {
        return service.createMap(str(body.get("name")));
    }

    @PutMapping("/geofencing/api/maps/{id}")
    @ResponseBody
    public Map<String, Object> renameMap(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.renameMap(id, str(body.get("name")));
    }

    @DeleteMapping("/geofencing/api/maps/{id}")
    @ResponseBody
    public Map<String, Object> deleteMap(@PathVariable long id) {
        service.deleteMap(id);
        return Map.of("deleted", id);
    }

    // ---------------- Points API ----------------

    @GetMapping("/geofencing/api/maps/{mapId}/points")
    @ResponseBody
    public List<Map<String, Object>> listPoints(@PathVariable long mapId) {
        return service.listPoints(mapId);
    }

    @PostMapping("/geofencing/api/maps/{mapId}/points")
    @ResponseBody
    public Map<String, Object> addPoint(@PathVariable long mapId, @RequestBody Map<String, Object> body) {
        return service.addPoint(mapId, str(body.get("label")),
                dbl(body.get("latitude")), dbl(body.get("longitude")), dbl(body.get("radiusM")),
                str(body.get("color")));
    }

    @PutMapping("/geofencing/api/points/{id}")
    @ResponseBody
    public Map<String, Object> updatePoint(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.updatePoint(id, str(body.get("label")),
                dbl(body.get("latitude")), dbl(body.get("longitude")), dbl(body.get("radiusM")),
                str(body.get("color")));
    }

    @DeleteMapping("/geofencing/api/points/{id}")
    @ResponseBody
    public Map<String, Object> deletePoint(@PathVariable long id) {
        service.deletePoint(id);
        return Map.of("deleted", id);
    }

    // ---------------- Error handling ----------------

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    // ---------------- Helpers ----------------

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static double dbl(Object o) {
        if (o == null) throw new IllegalArgumentException("Valore numerico mancante");
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Valore numerico non valido: " + o);
        }
    }
}
