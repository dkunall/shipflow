package com.shipflow.risk;

import java.util.List;

public record RiskAssessment(int score, String band, List<RiskFactor> factors, List<RiskAction> actions) {
}
