package com.shipflow.routing.domain;

import java.util.List;

public record RoutingSolution(String algorithm, List<Route> routes, List<Stop> unassigned) {
    public double totalKm() {
        return routes.stream().mapToDouble(Route::distanceKm).sum();
    }
}
