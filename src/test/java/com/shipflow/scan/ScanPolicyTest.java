package com.shipflow.scan;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.shipflow.shipment.domain.ShipmentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class ScanPolicyTest {
    private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

    @Test
    void appliesForwardScanWithInferredPath() {
        var d = ScanPolicy.decide(ALLOCATED, null, 7L, 7L, AT_HUB, T);
        assertThat(d.outcome()).isEqualTo(ScanOutcome.APPLIED);
        assertThat(d.path()).startsWith(PICKED_UP).endsWith(AT_HUB);
    }

    @Test
    void eventOlderThanCurrentStatusEventIsOutOfOrder() {
        var d = ScanPolicy.decide(OUT_FOR_DELIVERY, T, 7L, 7L, DELIVERED, T.minusSeconds(1));
        assertThat(d.outcome()).isEqualTo(ScanOutcome.OUT_OF_ORDER);
        assertThat(d.path()).isEmpty();
    }

    @Test
    void sameOrLowerStatusIsStale() {
        assertThat(ScanPolicy.decide(IN_TRANSIT, T, 7L, 7L, IN_TRANSIT, T.plusSeconds(5)).outcome()).isEqualTo(ScanOutcome.STALE);
        assertThat(ScanPolicy.decide(OUT_FOR_DELIVERY, T, 7L, 7L, PICKED_UP, T.plusSeconds(5)).outcome())
                .isEqualTo(ScanOutcome.STALE);
        assertThat(ScanPolicy.decide(DELIVERED, T, 7L, 7L, OUT_FOR_DELIVERY, T.plusSeconds(5)).outcome())
                .isEqualTo(ScanOutcome.STALE);
    }

    @Test
    void wrongCarrierOrUnassignedIsRejected() {
        assertThat(ScanPolicy.decide(ALLOCATED, null, 7L, 8L, PICKED_UP, T).outcome()).isEqualTo(ScanOutcome.REJECTED);
        assertThat(ScanPolicy.decide(CREATED, null, null, 8L, PICKED_UP, T).outcome()).isEqualTo(ScanOutcome.REJECTED);
    }

    @Test
    void unreachableHigherTargetIsRejected() {
        assertThat(ScanPolicy.decide(OUT_FOR_DELIVERY, T, 7L, 7L, RTO_IN_TRANSIT, T.plusSeconds(5)).outcome())
                .isEqualTo(ScanOutcome.REJECTED);
    }

    @Test
    void reattemptLoopIsAppliedEvenThoughRankIsEqual() {
        var d = ScanPolicy.decide(DELIVERY_FAILED, T, 7L, 7L, OUT_FOR_DELIVERY, T.plusSeconds(3600));
        assertThat(d.outcome()).isEqualTo(ScanOutcome.APPLIED);
        assertThat(d.path()).containsExactly(OUT_FOR_DELIVERY);
    }

    @Test
    void firstScanIsNeverOutOfOrderEvenWithCarrierClockSkew() {
        // status_event_at is null until a scan applies: booking time does not count
        assertThat(ScanPolicy.decide(ALLOCATED, null, 7L, 7L, PICKED_UP, T.minusSeconds(86_400)).outcome())
                .isEqualTo(ScanOutcome.APPLIED);
    }
}
