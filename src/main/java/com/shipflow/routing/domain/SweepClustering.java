package com.shipflow.routing.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sweep clustering (Gillett-Miller style): sort stops by polar angle around the hub, start the sweep at the
 * widest angular gap (so no natural cluster is cut in two), and cut the circular sequence into contiguous
 * sectors - one per rider - sized to respect each rider's capacity while balancing load.
 * Uses the fewest riders that can carry all stops (largest capacities first).
 */
public final class SweepClustering implements ClusteringStrategy {
    public static final String OVERFLOW = "";

    @Override
    public String name() {
        return "sweep";
    }

    @Override
    public Map<String, List<Stop>> cluster(GeoPoint depot, List<Stop> stops, List<RiderSlot> riders) {
        Map<String, List<Stop>> out = new LinkedHashMap<>();
        if (stops.isEmpty()) {
            return out;
        }
        List<Stop> sorted = new ArrayList<>(stops);
        sorted.sort(Comparator.comparingDouble((Stop s) -> angle(depot, s.point())).thenComparing(Stop::id));
        int start = widestGapStart(depot, sorted);
        List<Stop> ring = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            ring.add(sorted.get((start + i) % sorted.size()));
        }
        List<RiderSlot> byCap = new ArrayList<>(riders);
        byCap.sort(Comparator.comparingInt(RiderSlot::capacity).reversed().thenComparing(RiderSlot::riderId));
        int n = ring.size();
        int k = 0;
        long cum = 0;
        while (k < byCap.size() && cum < n) {
            cum += byCap.get(k++).capacity();
        }
        List<RiderSlot> used = byCap.subList(0, k);
        int remaining = n;
        int idx = 0;
        for (int i = 0; i < used.size() && remaining > 0; i++) {
            int capAfter = used.subList(i + 1, used.size()).stream().mapToInt(RiderSlot::capacity).sum();
            int balanced = (int) Math.ceil((double) remaining / (used.size() - i));
            int size = Math.min(used.get(i).capacity(), Math.max(balanced, remaining - capAfter));
            out.put(used.get(i).riderId(), new ArrayList<>(ring.subList(idx, idx + size)));
            idx += size;
            remaining -= size;
        }
        if (idx < n) {
            out.put(OVERFLOW, new ArrayList<>(ring.subList(idx, n)));
        }
        return out;
    }

    static double angle(GeoPoint depot, GeoPoint p) {
        double y = p.lat() - depot.lat();
        double x = (p.lng() - depot.lng()) * Math.cos(Math.toRadians(depot.lat()));
        return Math.atan2(y, x);
    }

    private static int widestGapStart(GeoPoint depot, List<Stop> sorted) {
        if (sorted.size() < 2) {
            return 0;
        }
        double best = -1;
        int bestStart = 0;
        for (int i = 0; i < sorted.size(); i++) {
            double a = angle(depot, sorted.get(i).point());
            double b = angle(depot, sorted.get((i + 1) % sorted.size()).point());
            double gap = b - a;
            if (gap <= 0) {
                gap += 2 * Math.PI;
            }
            if (gap > best) {
                best = gap;
                bestStart = (i + 1) % sorted.size();
            }
        }
        return bestStart;
    }
}
