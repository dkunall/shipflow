package com.shipflow.routing;

import com.shipflow.routing.domain.ClusterThenSequencePlanner;
import com.shipflow.routing.domain.GeoPoint;
import com.shipflow.routing.domain.NaiveBaselinePlanner;
import com.shipflow.routing.domain.NearestNeighborTwoOptSequencer;
import com.shipflow.routing.domain.RiderSlot;
import com.shipflow.routing.domain.Route;
import com.shipflow.routing.domain.RoutingSolution;
import com.shipflow.routing.domain.Stop;
import com.shipflow.routing.domain.SweepClustering;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RoutingAlgorithmsTest {
    private static final GeoPoint DEPOT = new GeoPoint(25.15, 55.23);

    static List<Stop> randomStops(int n, long seed) {
        Random r = new Random(seed);
        List<Stop> s = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            s.add(new Stop("s" + i, new GeoPoint(25.0 + r.nextDouble() * 0.35, 55.05 + r.nextDouble() * 0.4)));
        }
        return s;
    }

    static List<RiderSlot> riders(int n, int cap) {
        List<RiderSlot> r = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            r.add(new RiderSlot("r" + i, cap));
        }
        return r;
    }

    @Test
    void haversineMatchesKnownDistance() {
        // Dubai Mall -> Abu Dhabi Corniche ~ 118-125 km great-circle
        double km = new GeoPoint(25.1972, 55.2744).distanceKm(new GeoPoint(24.4764, 54.3705));
        assertThat(km).isBetween(115.0, 125.0);
        assertThat(DEPOT.distanceKm(DEPOT)).isZero();
        double[] box = DEPOT.boundingBox(10);
        assertThat(DEPOT.distanceKm(new GeoPoint(box[1], DEPOT.lng()))).isCloseTo(10, within(0.1));
    }

    @Test
    void sweepRespectsCapacityAndAssignsEveryStopOnce() {
        List<Stop> stops = randomStops(95, 1);
        Map<String, List<Stop>> clusters = new SweepClustering().cluster(DEPOT, stops, riders(5, 20));
        assertThat(clusters).doesNotContainKey(SweepClustering.OVERFLOW);
        Set<String> seen = new HashSet<>();
        clusters.values().forEach(c -> {
            assertThat(c.size()).isLessThanOrEqualTo(20);
            c.forEach(s -> assertThat(seen.add(s.id())).isTrue());
        });
        assertThat(seen).hasSize(95);
        // balanced: 95 over 5 riders => 19 each
        assertThat(clusters.values()).allMatch(c -> c.size() == 19);
    }

    @Test
    void overflowWhenRidersCannotCarryAll() {
        Map<String, List<Stop>> clusters = new SweepClustering().cluster(DEPOT, randomStops(30, 2), riders(2, 10));
        assertThat(clusters.get(SweepClustering.OVERFLOW)).hasSize(10);
        RoutingSolution sol = new ClusterThenSequencePlanner(new SweepClustering(), new NearestNeighborTwoOptSequencer())
                .solve(DEPOT, randomStops(30, 2), riders(2, 10));
        assertThat(sol.unassigned()).hasSize(10);
    }

    @Test
    void twoOptNeverWorseThanNearestNeighbourAndKeepsAllStops() {
        for (long seed = 1; seed <= 20; seed++) {
            List<Stop> stops = randomStops(40, seed);
            List<Stop> nnOnly = new NearestNeighborTwoOptSequencer(0).sequence(DEPOT, stops);
            List<Stop> opt = new NearestNeighborTwoOptSequencer().sequence(DEPOT, stops);
            assertThat(new HashSet<>(opt)).isEqualTo(new HashSet<>(stops));
            assertThat(Route.tourKm(DEPOT, opt)).isLessThanOrEqualTo(Route.tourKm(DEPOT, nnOnly) + 1e-9);
        }
    }

    @Test
    void optimisedPlanBeatsNaiveBaseline() {
        double opt = 0;
        double naive = 0;
        for (long seed = 1; seed <= 10; seed++) {
            List<Stop> stops = randomStops(120, seed);
            opt += new ClusterThenSequencePlanner(new SweepClustering(), new NearestNeighborTwoOptSequencer())
                    .solve(DEPOT, stops, riders(6, 25)).totalKm();
            naive += new NaiveBaselinePlanner().solve(DEPOT, stops, riders(6, 25)).totalKm();
        }
        assertThat(opt).isLessThan(naive * 0.6);
    }

    @Test
    void trivialInputs() {
        var planner = new ClusterThenSequencePlanner(new SweepClustering(), new NearestNeighborTwoOptSequencer());
        assertThat(planner.solve(DEPOT, List.of(), riders(1, 5)).routes()).isEmpty();
        var one = planner.solve(DEPOT, randomStops(1, 3), riders(1, 5));
        assertThat(one.routes()).hasSize(1);
        assertThat(one.totalKm()).isCloseTo(2 * DEPOT.distanceKm(one.routes().get(0).stops().get(0).point()), within(1e-9));
    }
}
