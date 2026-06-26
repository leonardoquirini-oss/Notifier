package com.containermgmt.eventcontrolprocessor.util;

/**
 * Great-circle distance utility (Haversine), extracted from GateEventProcessor's GateMatcher so the
 * GPS tolerance check stays consistent across the platform.
 */
public final class GeoDistance {

    private static final double EARTH_RADIUS_M = 6_371_000d;

    private GeoDistance() {
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }
}
