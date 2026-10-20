package com.shipflow.it;

import com.shipflow.edd.LaneLeadTimeRepository;
import com.shipflow.risk.RiskStatsRepository;
import com.shipflow.scorecard.ScorecardRepository;
import com.shipflow.stats.DeliveryOutcome;
import com.shipflow.stats.OutcomeLogRepository;
import com.shipflow.stats.StatsAggregator;
import com.shipflow.support.AbstractPostgresIT;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Write-behind statistics: concurrent aggregators fold every outcome exactly once (SKIP LOCKED + same-tx apply). */
class StatsAggregationIT extends AbstractPostgresIT {
    @Autowired OutcomeLogRepository log;
    @Autowired ScorecardRepository scorecards;
    @Autowired LaneLeadTimeRepository lanes;
    @Autowired RiskStatsRepository risk;
    @Autowired TransactionTemplate tx;

    @Test
    void concurrentAggregatorsFoldEachOutcomeExactlyOnce() throws Exception {
        thirdParty("FAST", "10.00", 2, 100);
        long carrier = jdbc.queryForObject("SELECT id FROM carrier WHERE code = 'FAST'", Long.class);
        long hub = jdbc.queryForObject("SELECT id FROM hub WHERE code = 'DXB-HUB'", Long.class);
        LocalDate day = LocalDate.of(2026, 10, 20);
        int delivered = 0;
        int failed = 0;
        int rto = 0;
        for (int i = 0; i < 503; i++) {
            DeliveryOutcome.Kind kind = i % 5 == 0 ? DeliveryOutcome.Kind.FAILED
                    : i % 7 == 0 ? DeliveryOutcome.Kind.RTO : DeliveryOutcome.Kind.DELIVERED;
            switch (kind) {
                case DELIVERED -> delivered++;
                case FAILED -> failed++;
                case RTO -> rto++;
            }
            log.append(UUID.randomUUID(), new DeliveryOutcome(kind, carrier, hub, "DXB-Z1", day.plusDays(i % 2),
                    kind == DeliveryOutcome.Kind.DELIVERED ? i % 3 : null, i % 2 == 0, i % 4 != 0,
                    i % 2 == 0 ? "DXB-001" : "DXB-002", "phone-" + (i % 10)));
        }
        // small batches so 8 aggregators really interleave on the same counter rows
        StatsAggregator small = new StatsAggregator(log, scorecards, lanes, risk, tx, new SimpleMeterRegistry(), 7);
        List<Object> folded = runConcurrently(8, i -> small::drainAll);
        assertThat(folded).allSatisfy(r -> assertThat(r).isInstanceOf(Integer.class));
        assertThat(folded.stream().mapToInt(r -> (Integer) r).sum()).isEqualTo(503);
        assertThat(log.backlog()).isZero();

        assertThat(count("SELECT sum(delivered) FROM carrier_scorecard_daily")).isEqualTo(delivered);
        assertThat(count("SELECT sum(rto) FROM carrier_scorecard_daily")).isEqualTo(rto);
        assertThat(count("SELECT count(*) FROM carrier_scorecard_daily")).isEqualTo(2);
        assertThat(count("SELECT sum(samples) FROM lane_lead_time")).isEqualTo(delivered);
        assertThat(count("SELECT sum(attempts) FROM pincode_delivery_stats")).isEqualTo(delivered + failed);
        assertThat(count("SELECT sum(failures) FROM pincode_delivery_stats")).isEqualTo(failed);
        assertThat(count("SELECT sum(attempts) FROM customer_delivery_stats")).isEqualTo(delivered + failed);
        assertThat(count("SELECT sum(rto) FROM customer_delivery_stats")).isEqualTo(rto);
        assertThat(small.drainAll()).isZero();
    }
}
