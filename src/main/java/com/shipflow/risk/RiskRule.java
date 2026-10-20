package com.shipflow.risk;

/**
 * Explainable delivery-risk rule (extension point). Pure function of {@link RiskInput}; returns points within
 * its declared budget so the total stays interpretable (sum of budgets of built-in rules = 100).
 */
public interface RiskRule {
    String code();

    int maxPoints();

    RiskFactor evaluate(RiskInput input);
}
