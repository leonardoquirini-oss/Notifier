package com.containermgmt.tfpeventingester.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * CRUD per mappe e punti di geofencing, persistiti su SQLite (datasource separato).
 */
@Service
public class GeofencingService {

    private final JdbcTemplate jdbc;

    public GeofencingService(@Qualifier("geofencingJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- Maps ----------------

    public List<Map<String, Object>> listMaps() {
        return jdbc.queryForList(
                "SELECT id, name, created_at FROM geo_map ORDER BY name COLLATE NOCASE");
    }

    public Map<String, Object> createMap(String name) {
        String n = requireName(name);
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO geo_map(name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, n);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        return getMap(id);
    }

    public Map<String, Object> renameMap(long id, String name) {
        String n = requireName(name);
        int rows = jdbc.update("UPDATE geo_map SET name = ? WHERE id = ?", n, id);
        if (rows == 0) throw new IllegalArgumentException("Mappa non trovata: " + id);
        return getMap(id);
    }

    public void deleteMap(long id) {
        // cascade manuale sui punti (PRAGMA foreign_keys puo' non bastare se non attivo sulla connessione)
        jdbc.update("DELETE FROM geo_point WHERE map_id = ?", id);
        int rows = jdbc.update("DELETE FROM geo_map WHERE id = ?", id);
        if (rows == 0) throw new IllegalArgumentException("Mappa non trovata: " + id);
    }

    private Map<String, Object> getMap(long id) {
        return jdbc.queryForMap("SELECT id, name, created_at FROM geo_map WHERE id = ?", id);
    }

    // ---------------- Points ----------------

    public List<Map<String, Object>> listPoints(long mapId) {
        return jdbc.queryForList(
                "SELECT id, map_id, label, latitude, longitude, radius_m, color, created_at " +
                "FROM geo_point WHERE map_id = ? ORDER BY id", mapId);
    }

    public Map<String, Object> addPoint(long mapId, String label, double lat, double lon, double radiusM, String color) {
        validateCoords(lat, lon, radiusM);
        String col = normalizeColor(color);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM geo_map WHERE id = ?", Integer.class, mapId);
        if (exists == null || exists == 0) throw new IllegalArgumentException("Mappa non trovata: " + mapId);
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO geo_point(map_id, label, latitude, longitude, radius_m, color) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, mapId);
            ps.setString(2, label);
            ps.setDouble(3, lat);
            ps.setDouble(4, lon);
            ps.setDouble(5, radiusM);
            ps.setString(6, col);
            return ps;
        }, kh);
        return getPoint(kh.getKey().longValue());
    }

    public Map<String, Object> updatePoint(long pointId, String label, double lat, double lon, double radiusM, String color) {
        validateCoords(lat, lon, radiusM);
        String col = normalizeColor(color);
        int rows = jdbc.update(
                "UPDATE geo_point SET label = ?, latitude = ?, longitude = ?, radius_m = ?, color = ? WHERE id = ?",
                label, lat, lon, radiusM, col, pointId);
        if (rows == 0) throw new IllegalArgumentException("Punto non trovato: " + pointId);
        return getPoint(pointId);
    }

    public void deletePoint(long pointId) {
        int rows = jdbc.update("DELETE FROM geo_point WHERE id = ?", pointId);
        if (rows == 0) throw new IllegalArgumentException("Punto non trovato: " + pointId);
    }

    private Map<String, Object> getPoint(long id) {
        return jdbc.queryForMap(
                "SELECT id, map_id, label, latitude, longitude, radius_m, color, created_at " +
                "FROM geo_point WHERE id = ?", id);
    }

    // ---------------- Validation ----------------

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Nome mappa obbligatorio");
        return name.trim();
    }

    /** Normalizza il colore a hex #RRGGBB; default blu se assente. */
    private static String normalizeColor(String color) {
        if (color == null || color.isBlank()) return "#0d6efd";
        String c = color.trim();
        if (!c.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Colore non valido (atteso #RRGGBB): " + color);
        }
        return c.toLowerCase();
    }

    private static void validateCoords(double lat, double lon, double radiusM) {
        if (lat < -90 || lat > 90) throw new IllegalArgumentException("Latitudine fuori range [-90,90]: " + lat);
        if (lon < -180 || lon > 180) throw new IllegalArgumentException("Longitudine fuori range [-180,180]: " + lon);
        if (radiusM <= 0) throw new IllegalArgumentException("Raggio deve essere > 0: " + radiusM);
    }
}
