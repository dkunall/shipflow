package com.shipflow.allocation.domain;

import java.util.Optional;

/**
 * Hard constraint (extension point). Returns a human-readable rejection reason, or empty if the candidate
 * passes. Filters are evaluated in {@code @Order}; the first rejection is recorded in the explanation.
 */
public interface CandidateFilter {
    String name();

    Optional<String> reject(CarrierCandidate candidate, AllocationContext context);
}
