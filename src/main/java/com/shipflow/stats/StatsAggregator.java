package com.shipflow.stats;

import com.shipflow.edd.LaneLeadTimeRepository;
import com.shipflow.risk.RiskStatsRepository;
import com.shipflow.scorecard.ScorecardRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Write-behind fold of {@code delivery_outcome} into the scorecard / lane / risk counters.
 * Each batch is claimed and applied in ONE transaction (exactly-once; crash = rollback = retried next tick).
 * N outcomes for the same carrier/zone/day become a single UPSERT, so the hot rows are touched once per batch
 * instead of once per scan. Staleness is bounded by the flush interval (default 1 s).
 */
@Service
public class StatsAggregator {
    private final OutcomeLogRepository log;
    private final ScorecardRepository scorecards;
    private final LaneLeadTimeRepository lanes;
    private final RiskStatsRepository risk;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final int batchSize;

    public StatsAggregator(OutcomeLogRepository log, ScorecardRepository scorecards, LaneLeadTimeRepository lanes,
                           RiskStatsRepository risk, TransactionTemplate tx, MeterRegistry meters,
                           @Value("${shipflow.stats.batch-size:5000}") int batchSize) {
        this.log = log;
        this.scorecards = scorecards;
        this.lanes = lanes;
        this.risk = risk;
        this.tx = tx;
        this.meters = meters;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${shipflow.stats.flush-interval:PT1S}")
    public void scheduledFlush() {
        drainAll();
    }

    /** Applies all pending outcomes; returns how many were folded. */
    public int drainAll() {
        int total = 0;
        int n;
        do {
            n = drainOnce();
            total += n;
        } while (n == batchSize);
        return total;
    }

    public int drainOnce() {
        Integer n = tx.execute(status -> {
            List<DeliveryOutcome> batch = log.claim(batchSize);
            if (batch.isEmpty()) {
                return 0;
            }
            StatsDeltas d = StatsDeltas.of(batch);
            scorecards.add(d.scorecards());
            lanes.add(d.lanes());
            risk.addPincodes(d.pincodes());
            risk.addCustomers(d.customers());
            return batch.size();
        });
        int folded = n == null ? 0 : n;
        if (folded > 0) {
            meters.counter("shipflow.stats.outcomes.folded").increment(folded);
        }
        return folded;
    }
}
