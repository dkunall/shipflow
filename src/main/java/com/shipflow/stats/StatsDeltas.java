package com.shipflow.stats;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure fold of a batch of outcomes into one increment per counter row. Keys are kept sorted so concurrent
 * aggregators always lock rows in the same order (no deadlocks).
 */
public record StatsDeltas(Map<ScorecardKey, Scorecard> scorecards, Map<LaneKey, Integer> lanes,
                          Map<String, Attempts> pincodes, Map<String, Customer> customers) {

    public record ScorecardKey(long carrierId, String zone, LocalDate day) {
    }

    public record LaneKey(long carrierId, long originHubId, String zone, int leadDays) {
    }

    public record Scorecard(int delivered, int onTime, int firstAttempt, int rto) {
        Scorecard plus(Scorecard o) {
            return new Scorecard(delivered + o.delivered, onTime + o.onTime, firstAttempt + o.firstAttempt, rto + o.rto);
        }
    }

    public record Attempts(int attempts, int failures) {
        Attempts plus(Attempts o) {
            return new Attempts(attempts + o.attempts, failures + o.failures);
        }
    }

    public record Customer(int attempts, int failures, int rto) {
        Customer plus(Customer o) {
            return new Customer(attempts + o.attempts, failures + o.failures, rto + o.rto);
        }
    }

    private static final Comparator<ScorecardKey> SCORECARD_ORDER = Comparator.comparingLong(ScorecardKey::carrierId)
            .thenComparing(ScorecardKey::zone).thenComparing(ScorecardKey::day);
    private static final Comparator<LaneKey> LANE_ORDER = Comparator.comparingLong(LaneKey::carrierId)
            .thenComparingLong(LaneKey::originHubId).thenComparing(LaneKey::zone).thenComparingInt(LaneKey::leadDays);

    public static StatsDeltas of(List<DeliveryOutcome> outcomes) {
        Map<ScorecardKey, Scorecard> sc = new TreeMap<>(SCORECARD_ORDER);
        Map<LaneKey, Integer> lanes = new TreeMap<>(LANE_ORDER);
        Map<String, Attempts> pin = new TreeMap<>();
        Map<String, Customer> cust = new TreeMap<>();
        for (DeliveryOutcome o : outcomes) {
            switch (o.kind()) {
                case DELIVERED -> {
                    if (o.carrierId() != null) {
                        sc.merge(new ScorecardKey(o.carrierId(), o.zoneCode(), o.localDay()),
                                new Scorecard(1, o.onTime() ? 1 : 0, o.firstAttempt() ? 1 : 0, 0), Scorecard::plus);
                        if (o.leadDays() != null && o.originHubId() != null) {
                            lanes.merge(new LaneKey(o.carrierId(), o.originHubId(), o.zoneCode(), o.leadDays()), 1,
                                    Integer::sum);
                        }
                    }
                    pin.merge(o.pincode(), new Attempts(1, 0), Attempts::plus);
                    cust.merge(o.phoneHash(), new Customer(1, 0, 0), Customer::plus);
                }
                case FAILED -> {
                    pin.merge(o.pincode(), new Attempts(1, 1), Attempts::plus);
                    cust.merge(o.phoneHash(), new Customer(1, 1, 0), Customer::plus);
                }
                case RTO -> {
                    if (o.carrierId() != null) {
                        sc.merge(new ScorecardKey(o.carrierId(), o.zoneCode(), o.localDay()), new Scorecard(0, 0, 0, 1),
                                Scorecard::plus);
                    }
                    cust.merge(o.phoneHash(), new Customer(0, 0, 1), Customer::plus);
                }
            }
        }
        return new StatsDeltas(sc, lanes, pin, cust);
    }
}
