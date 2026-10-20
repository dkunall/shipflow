package com.shipflow.routing.domain;

public record GeoPoint(double lat, double lng) {
    private static final double EARTH_RADIUS_KM = 6371.0088;

    /** Great-circle distance (haversine), km. */
    public double distanceKm(GeoPoint o) {
        double dLat = Math.toRadians(o.lat - lat);
        double dLng = Math.toRadians(o.lng - lng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(o.lat)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Lat/lng bounding box of a radius - cheap prefilter before exact haversine (index friendly). */
    public double[] boundingBox(double radiusKm) {
        double dLat = Math.toDegrees(radiusKm / EARTH_RADIUS_KM);
        double dLng = Math.toDegrees(radiusKm / (EARTH_RADIUS_KM * Math.max(0.01, Math.cos(Math.toRadians(lat)))));
        return new double[]{lat - dLat, lat + dLat, lng - dLng, lng + dLng};
    }
}
