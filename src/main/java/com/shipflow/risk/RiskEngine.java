package com.shipflow.risk;

import com.shipflow.config.ShipFlowProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Sums the factors of all {@link RiskRule}s (capped at 100), bands the score and derives actions:
 * HIGH -> delivery OTP (+ prepaid nudge when COD); MEDIUM + COD -> delivery OTP;
 * weak address (address factor >= 15) -> verification call. Deterministic and explainable - not ML.
 */
@Component
public class RiskEngine {
    private final List<RiskRule> rules;
    private final int medium;
    private final int high;

    public RiskEngine(List<RiskRule> rules, ShipFlowProperties props) {
        this.rules = List.copyOf(rules);
        this.medium = props.risk().mediumThreshold();
        this.high = props.risk().highThreshold();
    }

    public RiskAssessment assess(RiskInput input) {
        List<RiskFactor> factors = new ArrayList<>();
        int total = 0;
        for (RiskRule r : rules) {
            RiskFactor f = r.evaluate(input);
            factors.add(f);
            total += f.points();
        }
        int score = Math.min(100, total);
        String band = score >= high ? "HIGH" : score >= medium ? "MEDIUM" : "LOW";
        List<RiskAction> actions = new ArrayList<>();
        if ("HIGH".equals(band) || ("MEDIUM".equals(band) && input.isCod())) {
            actions.add(RiskAction.REQUIRE_DELIVERY_OTP);
        }
        if ("HIGH".equals(band) && input.isCod()) {
            actions.add(RiskAction.PREPAID_CONVERSION_NUDGE);
        }
        boolean weakAddress = factors.stream()
                .anyMatch(f -> f.rule().equals(com.shipflow.risk.rules.AddressQualityRule.CODE) && f.points() >= 15);
        if (weakAddress) {
            actions.add(RiskAction.ADDRESS_VERIFICATION_CALL);
        }
        return new RiskAssessment(score, band, List.copyOf(factors), List.copyOf(actions));
    }
}
