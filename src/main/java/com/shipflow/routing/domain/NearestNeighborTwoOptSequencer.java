package com.shipflow.routing.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Nearest-neighbour construction from the hub, then 2-opt local search on the closed tour until no improving
 * segment reversal exists (first-improvement, bounded passes). Distances are precomputed once (n <= rider
 * capacity, so the O(n^2) matrix is tiny).
 */
public final class NearestNeighborTwoOptSequencer implements RouteSequencer {
    private static final double EPS = 1e-9;
    private final int maxPasses;

    public NearestNeighborTwoOptSequencer() {
        this(50);
    }

    public NearestNeighborTwoOptSequencer(int maxPasses) {
        this.maxPasses = maxPasses;
    }

    @Override
    public String name() {
        return "nearest-neighbor+2-opt";
    }

    @Override
    public List<Stop> sequence(GeoPoint depot, List<Stop> stops) {
        int n = stops.size();
        if (n <= 2) {
            return List.copyOf(stops);
        }
        // node 0 = depot, 1..n = stops
        GeoPoint[] pts = new GeoPoint[n + 1];
        pts[0] = depot;
        for (int i = 0; i < n; i++) {
            pts[i + 1] = stops.get(i).point();
        }
        double[][] d = new double[n + 1][n + 1];
        for (int i = 0; i <= n; i++) {
            for (int j = i + 1; j <= n; j++) {
                d[i][j] = d[j][i] = pts[i].distanceKm(pts[j]);
            }
        }
        int[] tour = nearestNeighbor(d, n);
        twoOpt(tour, d);
        List<Stop> out = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            out.add(stops.get(tour[i] - 1));
        }
        return out;
    }

    /** tour[0] = 0 (depot); tour has n+1 entries; closing edge back to depot is implicit. */
    static int[] nearestNeighbor(double[][] d, int n) {
        int[] tour = new int[n + 1];
        boolean[] seen = new boolean[n + 1];
        seen[0] = true;
        int cur = 0;
        for (int pos = 1; pos <= n; pos++) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int j = 1; j <= n; j++) {
                if (!seen[j] && d[cur][j] < bestD) {
                    bestD = d[cur][j];
                    best = j;
                }
            }
            tour[pos] = best;
            seen[best] = true;
            cur = best;
        }
        return tour;
    }

    /** Classic 2-opt on a closed tour with the depot fixed at position 0. */
    void twoOpt(int[] tour, double[][] d) {
        int m = tour.length; // positions 0..m-1, edge (m-1 -> 0) closes the tour
        boolean improved = true;
        for (int pass = 0; improved && pass < maxPasses; pass++) {
            improved = false;
            for (int i = 0; i < m - 1; i++) {
                for (int j = i + 2; j < m; j++) {
                    int a = tour[i], b = tour[i + 1], c = tour[j], e = tour[(j + 1) % m];
                    if (a == e) {
                        continue;
                    }
                    double delta = d[a][c] + d[b][e] - d[a][b] - d[c][e];
                    if (delta < -EPS) {
                        reverse(tour, i + 1, j);
                        improved = true;
                    }
                }
            }
        }
    }

    private static void reverse(int[] t, int from, int to) {
        while (from < to) {
            int tmp = t[from];
            t[from++] = t[to];
            t[to--] = tmp;
        }
    }
}
