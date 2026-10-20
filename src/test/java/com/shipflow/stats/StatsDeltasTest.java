package com.shipflow.stats;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatsDeltasTest {
    private static final LocalDate D = LocalDate.of(2026, 10, 1);

    private static DeliveryOutcome o(DeliveryOutcome.Kind k, long carrier, Integer lead, boolean onTime, boolean first) {
        return new DeliveryOutcome(k, carrier, 9L, "Z1", D, lead, onTime, first, "P1", "h1");
    }

    @Test
    void foldsManyOutcomesIntoOneIncrementPerRow() {
        StatsDeltas d = StatsDeltas.of(List.of(
                o(DeliveryOutcome.Kind.DELIVERED, 2, 1, true, true),
                o(DeliveryOutcome.Kind.DELIVERED, 2, 1, false, true),
                o(DeliveryOutcome.Kind.DELIVERED, 2, 3, true, false),
                o(DeliveryOutcome.Kind.FAILED, 2, null, false, false),
                o(DeliveryOutcome.Kind.RTO, 2, null, false, false),
                o(DeliveryOutcome.Kind.DELIVERED, 1, null, true, true)));
        assertThat(d.scorecards()).hasSize(2);
        assertThat(d.scorecards().get(new StatsDeltas.ScorecardKey(2, "Z1", D)))
                .isEqualTo(new StatsDeltas.Scorecard(3, 2, 2, 1));
        assertThat(d.lanes()).containsEntry(new StatsDeltas.LaneKey(2, 9, "Z1", 1), 2)
                .containsEntry(new StatsDeltas.LaneKey(2, 9, "Z1", 3), 1).hasSize(2); // null lead => no sample
        assertThat(d.pincodes().get("P1")).isEqualTo(new StatsDeltas.Attempts(5, 1));
        assertThat(d.customers().get("h1")).isEqualTo(new StatsDeltas.Customer(5, 1, 1));
        // deterministic lock order: carrier 1 before carrier 2
        assertThat(d.scorecards().keySet().iterator().next().carrierId()).isEqualTo(1);
    }

    @Test
    void outcomesWithoutCarrierOnlyFeedRiskStats() {
        StatsDeltas d = StatsDeltas.of(List.of(new DeliveryOutcome(DeliveryOutcome.Kind.RTO, null, null, "Z1", D, null,
                false, false, "P1", "h1")));
        assertThat(d.scorecards()).isEmpty();
        assertThat(d.pincodes()).isEmpty();
        assertThat(d.customers().get("h1")).isEqualTo(new StatsDeltas.Customer(0, 0, 1));
    }
}
