package com.shipflow.scorecard;

import com.shipflow.config.ShipFlowProperties;

/**
 * Pure scorecard math.
 * <pre>
 *   smoothed(successes, n) = (successes + prior * k) / (n + k)
 *   on-time %       = on_time / delivered
 *   first-attempt % = first_attempt / delivered
 *   RTO %           = rto / (delivered + rto)
 * </pre>
 * k ("prior weight") = how many pseudo-observations the prior is worth.
 */
public final class ScorecardMath {
    private ScorecardMath() {
    }

    public static double smoothed(long successes, long n, double prior, double k) {
        if (successes < 0 || n < 0 || successes > n) {
            throw new IllegalArgumentException("invalid counts " + successes + "/" + n);
        }
        if (n + k == 0) {
            return prior;
        }
        return (successes + prior * k) / (n + k);
    }

    public static CarrierScorecard scorecard(long delivered, long onTime, long firstAttempt, long rto,
                                             ShipFlowProperties.Scorecard p) {
        return new CarrierScorecard(delivered, onTime, firstAttempt, rto,
                smoothed(onTime, delivered, p.priorOnTime(), p.priorWeight()),
                smoothed(firstAttempt, delivered, p.priorFirstAttempt(), p.priorWeight()),
                smoothed(rto, delivered + rto, p.priorRto(), p.priorWeight()));
    }
}
