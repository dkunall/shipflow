package com.shipflow.routing.domain;

import java.util.List;

/** Orders the stops of one rider's cluster. */
public interface RouteSequencer {
    String name();

    List<Stop> sequence(GeoPoint depot, List<Stop> stops);
}
