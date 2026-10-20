package com.shipflow.routing.domain;

import java.util.List;
import java.util.Map;

/** Splits stops into rider-sized groups (capacity respected). Unassignable stops go to key "" (overflow). */
public interface ClusteringStrategy {
    String name();

    Map<String, List<Stop>> cluster(GeoPoint depot, List<Stop> stops, List<RiderSlot> riders);
}
