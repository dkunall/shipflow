package com.shipflow.routing.domain;

import java.util.List;

/** Closed tour depot -> stops (in order) -> depot. */
public record Route(String riderId, List<Stop> stops, double distanceKm) {
    public static double tourKm(GeoPoint depot, List<Stop> stops) {
        if (stops.isEmpty()) {
            return 0;
        }
        double d = depot.distanceKm(stops.get(0).point());
        for (int i = 1; i < stops.size(); i++) {
            d += stops.get(i - 1).point().distanceKm(stops.get(i).point());
        }
        return d + stops.get(stops.size() - 1).point().distanceKm(depot);
    }
}
