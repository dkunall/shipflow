package com.shipflow.allocation.domain;

import java.util.List;

/** Ranked eligible candidates (best first) + rejected candidates with reasons. */
public record AllocationPlan(AllocationContext context, List<ScoredCandidate> ranked, List<Rejection> rejected) {

    public record ScoredCandidate(PricedCandidate priced, List<RuleScore> scores, double total) {
        public String code() {
            return priced.code();
        }
    }

    public record Rejection(String carrier, String filter, String reason) {
    }
}
