package com.shipflow.routing.domain;

import java.util.ArrayList;
import java.util.List;

/** Baseline: stops in creation order, filled rider by rider up to capacity, visited in that order. */
public final class NaiveBaselinePlanner implements RoutingAlgorithm {
    @Override
    public RoutingSolution solve(GeoPoint depot, List<Stop> stops, List<RiderSlot> riders) {
        List<Route> routes = new ArrayList<>();
        int idx = 0;
        for (RiderSlot r : riders) {
            if (idx >= stops.size()) {
                break;
            }
            int end = Math.min(stops.size(), idx + r.capacity());
            List<Stop> seq = List.copyOf(stops.subList(idx, end));
            routes.add(new Route(r.riderId(), seq, Route.tourKm(depot, seq)));
            idx = end;
        }
        return new RoutingSolution("naive-creation-order", routes, List.copyOf(stops.subList(idx, stops.size())));
    }
}
