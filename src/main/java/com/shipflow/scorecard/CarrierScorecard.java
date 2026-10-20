package com.shipflow.scorecard;

/**
 * Rolling-window performance of one carrier in one zone. Rates are Bayesian-smoothed (see {@link ScorecardMath})
 * so a carrier with 2 deliveries does not look perfect/terrible; raw counts are kept for explanations.
 */
public record CarrierScorecard(long delivered, long onTime, long firstAttempt, long rto,
                               double onTimeRate, double firstAttemptRate, double rtoRate) {
}
