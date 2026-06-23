package com.containermgmt.gateeventprocessor.service;

import com.containermgmt.gateeventprocessor.model.Gate;
import com.containermgmt.gateeventprocessor.repository.GateRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Matches a (latitude, longitude) point against the gates loaded from BERLink wrk_gates,
 * returning the closest gate whose radius covers the point.
 */
@Service
@Slf4j
public class GateMatcher {

    private static final double EARTH_RADIUS_M = 6_371_000d;

    private final GateRepository gateRepository;
    private volatile List<Gate> gates = List.of();

    public GateMatcher(GateRepository gateRepository) {
        this.gateRepository = gateRepository;
    }

    @PostConstruct
    public void load() {
        this.gates = gateRepository.findAll();
        log.info("GateMatcher loaded {} gate(s): {}",
                gates.size(),
                gates.stream().map(Gate::getLabel).toList());
    }

    /** Result of a successful match. */
    public record Match(String gateId, Gate gate, double distanceMeters) {}

    /**
     * Returns the nearest gate within its radius, or null if the point is outside all gates
     * or coordinates are missing.
     */
    public Match match(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return null;
        }
        Match best = null;
        for (Gate gate : gates) {
            double distance = haversineMeters(latitude, longitude, gate.getLatitude(), gate.getLongitude());
            if (distance <= gate.getRadius() && (best == null || distance < best.distanceMeters)) {
                best = new Match(gate.getLabel(), gate, distance);
            }
        }
        return best;
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }
}
