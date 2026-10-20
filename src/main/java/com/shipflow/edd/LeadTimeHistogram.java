package com.shipflow.edd;

import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/** Histogram of observed lead times (business days) for a lane; percentile = nearest-rank method. */
public final class LeadTimeHistogram {
    private final TreeMap<Integer, Long> counts = new TreeMap<>();
    private long samples;

    public static LeadTimeHistogram empty() {
        return new LeadTimeHistogram();
    }

    public static LeadTimeHistogram of(Map<Integer, Long> buckets) {
        LeadTimeHistogram h = new LeadTimeHistogram();
        buckets.forEach(h::add);
        return h;
    }

    public LeadTimeHistogram add(int leadDays, long n) {
        if (leadDays < 0 || n < 0) {
            throw new IllegalArgumentException("negative lead time / count");
        }
        if (n > 0) {
            counts.merge(leadDays, n, Long::sum);
            samples += n;
        }
        return this;
    }

    public long samples() {
        return samples;
    }

    /** Smallest lead time L such that at least ceil(p * samples) observations are <= L. */
    public OptionalInt percentile(double p) {
        if (p <= 0 || p > 1) {
            throw new IllegalArgumentException("p must be in (0,1]");
        }
        if (samples == 0) {
            return OptionalInt.empty();
        }
        long rank = (long) Math.ceil(p * samples);
        long cumulative = 0;
        for (var e : counts.entrySet()) {
            cumulative += e.getValue();
            if (cumulative >= rank) {
                return OptionalInt.of(e.getKey());
            }
        }
        return OptionalInt.of(counts.lastKey());
    }
}
