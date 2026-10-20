package com.shipflow.risk;

/** One rule's contribution: points in [0, maxPoints] with the reason. */
public record RiskFactor(String rule, int points, int maxPoints, String reason) {
    public RiskFactor {
        if (points < 0 || points > maxPoints) {
            throw new IllegalArgumentException(rule + ": points " + points + " outside [0," + maxPoints + "]");
        }
    }
}
