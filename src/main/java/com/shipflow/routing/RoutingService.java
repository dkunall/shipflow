package com.shipflow.routing;

import com.shipflow.common.error.ShipFlowException;
import com.shipflow.config.ShipFlowProperties;
import com.shipflow.network.HubInfo;
import com.shipflow.network.NetworkDirectory;
import com.shipflow.routing.domain.ClusterThenSequencePlanner;
import com.shipflow.routing.domain.GeoPoint;
import com.shipflow.routing.domain.NaiveBaselinePlanner;
import com.shipflow.routing.domain.NearestNeighborTwoOptSequencer;
import com.shipflow.routing.domain.RiderSlot;
import com.shipflow.routing.domain.Route;
import com.shipflow.routing.domain.RoutingAlgorithm;
import com.shipflow.routing.domain.RoutingSolution;
import com.shipflow.routing.domain.Stop;
import com.shipflow.routing.domain.SweepClustering;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds a rider batching + stop-ordering plan for a hub's OUT_FOR_DELIVERY shipments. Geo filtering uses a
 * lat/lng bounding box in SQL (plain columns, no PostGIS) followed by exact haversine in Java. The plan is
 * computed in memory (no transaction held) and persisted in one short transaction with the naive baseline
 * distance for comparison.
 */
@Service
public class RoutingService {
    public record PlannedStop(int seq, UUID shipmentId, double lat, double lng, double legKm) {
    }

    public record PlannedRoute(long riderId, String riderCode, int stops, double distanceKm, List<PlannedStop> sequence) {
    }

    public record Plan(long planId, String hubCode, String algorithm, int stops, int ridersUsed, int unassigned,
                       double optimizedKm, double baselineKm, double savingsPercent, long computeMs,
                       List<PlannedRoute> routes) {
    }

    private record Rider(long id, String code, int capacity) {
    }

    private final JdbcTemplate jdbc;
    private final NetworkDirectory network;
    private final TransactionTemplate tx;
    private final int maxStops;
    private final RoutingAlgorithm optimizer = new ClusterThenSequencePlanner(new SweepClustering(),
            new NearestNeighborTwoOptSequencer());
    private final RoutingAlgorithm baseline = new NaiveBaselinePlanner();

    public RoutingService(JdbcTemplate jdbc, NetworkDirectory network, TransactionTemplate tx, ShipFlowProperties props) {
        this.jdbc = jdbc;
        this.network = network;
        this.tx = tx;
        this.maxStops = props.routing().maxStops();
    }

    public Plan plan(String hubCode, double radiusKm, String actor) {
        HubInfo hub = network.hub(hubCode).orElseThrow(() -> ShipFlowException.notFound("hub", hubCode));
        GeoPoint depot = new GeoPoint(hub.lat(), hub.lng());
        double[] box = depot.boundingBox(radiusKm);
        List<Stop> stops = jdbc.query("""
                SELECT id, dest_lat, dest_lng FROM shipment
                 WHERE delivery_hub_id = ? AND status = 'OUT_FOR_DELIVERY'
                   AND dest_lat BETWEEN ? AND ? AND dest_lng BETWEEN ? AND ?
                 ORDER BY created_at, id LIMIT ?
                """, (rs, i) -> new Stop(rs.getString(1), new GeoPoint(rs.getDouble(2), rs.getDouble(3))),
                hub.id(), box[0], box[1], box[2], box[3], maxStops)
                .stream().filter(s -> depot.distanceKm(s.point()) <= radiusKm).toList();
        List<Rider> riders = jdbc.query("SELECT id, code, capacity FROM rider WHERE hub_id = ? AND active ORDER BY code",
                (rs, i) -> new Rider(rs.getLong(1), rs.getString(2), rs.getInt(3)), hub.id());
        if (riders.isEmpty()) {
            throw ShipFlowException.unprocessable("no active riders at hub " + hubCode);
        }
        List<RiderSlot> slots = riders.stream().map(r -> new RiderSlot(String.valueOf(r.id()), r.capacity())).toList();
        long t0 = System.nanoTime();
        RoutingSolution opt = optimizer.solve(depot, stops, slots);
        long computeMs = (System.nanoTime() - t0) / 1_000_000;
        RoutingSolution base = baseline.solve(depot, stops, slots);
        Map<String, Rider> byId = riders.stream().collect(Collectors.toMap(r -> String.valueOf(r.id()), Function.identity()));

        List<PlannedRoute> routes = new ArrayList<>();
        for (Route r : opt.routes()) {
            List<PlannedStop> seq = new ArrayList<>();
            GeoPoint prev = depot;
            int k = 1;
            for (Stop s : r.stops()) {
                seq.add(new PlannedStop(k++, UUID.fromString(s.id()), s.point().lat(), s.point().lng(),
                        round(prev.distanceKm(s.point()))));
                prev = s.point();
            }
            Rider rider = byId.get(r.riderId());
            routes.add(new PlannedRoute(rider.id(), rider.code(), r.stops().size(), round(r.distanceKm()), seq));
        }
        double optKm = round(opt.totalKm());
        double baseKm = round(base.totalKm());
        long planId = tx.execute(s -> persist(hub.id(), opt.algorithm(), stops.size(), routes, opt.unassigned().size(),
                optKm, baseKm, computeMs, actor));
        double savings = baseKm == 0 ? 0 : round(100.0 * (baseKm - optKm) / baseKm);
        return new Plan(planId, hubCode, opt.algorithm(), stops.size(), routes.size(), opt.unassigned().size(), optKm,
                baseKm, savings, computeMs, routes);
    }

    private long persist(long hubId, String algorithm, int stops, List<PlannedRoute> routes, int unassigned,
                         double optKm, double baseKm, long ms, String actor) {
        long id = jdbc.queryForObject("""
                INSERT INTO route_plan (hub_id, algorithm, stops, riders_used, unassigned, optimized_km, baseline_km,
                                        compute_ms, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, hubId, algorithm, stops, routes.size(), unassigned, BigDecimal.valueOf(optKm),
                BigDecimal.valueOf(baseKm), (int) ms, actor);
        List<Object[]> rows = new ArrayList<>();
        for (PlannedRoute r : routes) {
            for (PlannedStop s : r.sequence()) {
                rows.add(new Object[]{id, r.riderId(), s.seq(), s.shipmentId(),
                        BigDecimal.valueOf(s.legKm()).setScale(3, RoundingMode.HALF_UP)});
            }
        }
        jdbc.batchUpdate("INSERT INTO route_stop (plan_id, rider_id, seq, shipment_id, leg_km) VALUES (?, ?, ?, ?, ?)", rows);
        return id;
    }

    static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
