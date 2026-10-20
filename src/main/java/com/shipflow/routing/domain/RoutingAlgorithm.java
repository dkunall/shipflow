package com.shipflow.routing.domain;

import java.util.List;

public interface RoutingAlgorithm {
    RoutingSolution solve(GeoPoint depot, List<Stop> stops, List<RiderSlot> riders);
}
