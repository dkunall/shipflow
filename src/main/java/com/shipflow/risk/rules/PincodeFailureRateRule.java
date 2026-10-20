package com.shipflow.risk.rules;

import com.shipflow.risk.RiskFactor;
import com.shipflow.risk.RiskInput;
import com.shipflow.risk.RiskRule;
import com.shipflow.scorecard.ScorecardMath;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Historical failed-attempt rate of the destination area (max 25). Smoothed towards a 10% prior with weight 20,
 * then mapped linearly: 0% -> 0 points, >= 50% -> 25 points.
 */
@Component
@Order(20)
public class PincodeFailureRateRule implements RiskRule {
    public static final String CODE = "PINCODE_FAILURE_RATE";
    static final double PRIOR = 0.10;
    static final double PRIOR_WEIGHT = 20;

    public String code() {
        return CODE;
    }

    public int maxPoints() {
        return 25;
    }

    public RiskFactor evaluate(RiskInput in) {
        var s = in.pincodeStats() == null ? RiskInput.DeliveryStats.NONE : in.pincodeStats();
        double rate = ScorecardMath.smoothed(s.failures(), s.attempts(), PRIOR, PRIOR_WEIGHT);
        int points = (int) Math.round(Math.min(1.0, rate / 0.5) * maxPoints());
        return new RiskFactor(CODE, points, maxPoints(),
                String.format("area failure rate %.1f%% (%d/%d attempts, smoothed)", rate * 100, s.failures(), s.attempts()));
    }
}
