package com.shipflow.allocation.domain;

/** One rule's verdict for one candidate: normalised score in [0,1], effective weight, and why. */
public record RuleScore(String rule, double score, double weight, String detail) {
    public RuleScore {
        if (Double.isNaN(score) || score < 0 || score > 1) {
            throw new IllegalArgumentException("rule " + rule + " produced score outside [0,1]: " + score);
        }
    }

    public double contribution() {
        return score * weight;
    }
}
