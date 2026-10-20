package com.shipflow.routing.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Composition: any clustering strategy + any sequencer (both swappable). */
public final class ClusterThenSequencePlanner implements RoutingAlgorithm {
    private final ClusteringStrategy clustering;
    private final RouteSequencer sequencer;

    public ClusterThenSequencePlanner(ClusteringStrategy clustering, RouteSequencer sequencer) {
        this.clustering = clustering;
        this.sequencer = sequencer;
    }

    @Override
    public RoutingSolution solve(GeoPoint depot, List<Stop> stops, List<RiderSlot> riders) {
        Map<String, List<Stop>> clusters = clustering.cluster(depot, stops, riders);
        List<Route> routes = new ArrayList<>();
        List<Stop> unassigned = List.of();
        for (var e : clusters.entrySet()) {
            if (e.getKey().equals(SweepClustering.OVERFLOW)) {
                unassigned = List.copyOf(e.getValue());
                continue;
            }
            List<Stop> seq = sequencer.sequence(depot, e.getValue());
            routes.add(new Route(e.getKey(), seq, Route.tourKm(depot, seq)));
        }
        return new RoutingSolution(clustering.name() + "+" + sequencer.name(), routes, unassigned);
    }
}
