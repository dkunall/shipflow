package com.shipflow.allocation.domain;

/**
 * Soft scoring rule (extension point / strategy). Produces a normalised score in [0,1] (1 = best) for a
 * candidate relative to the pool. Weight comes from configuration ({@code shipflow.allocation.weights.<key>});
 * a rule may adapt its weight to the context (e.g. first-attempt matters more for high-risk shipments).
 * The engine sums weighted scores - no switch statements, rules are composed as a list.
 */
public interface AllocationRule {
    String key();

    double score(PricedCandidate candidate, CandidatePool pool, AllocationContext context);

    String detail(PricedCandidate candidate);

    default double effectiveWeight(double configuredWeight, AllocationContext context) {
        return configuredWeight;
    }
}
