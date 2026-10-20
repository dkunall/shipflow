package com.shipflow.risk.rules;

import com.shipflow.config.ShipFlowProperties;
import com.shipflow.risk.RiskFactor;
import com.shipflow.risk.RiskInput;
import com.shipflow.risk.RiskRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;

/** COD exposure (max 25): prepaid 0; COD 5 base + up to 20 scaled by amount / highCodAmount (capped). */
@Component
@Order(30)
public class CodAmountRule implements RiskRule {
    public static final String CODE = "COD_AMOUNT";
    private final BigDecimal high;

    public CodAmountRule(ShipFlowProperties props) {
        this.high = props.risk().highCodAmount();
    }

    public String code() {
        return CODE;
    }

    public int maxPoints() {
        return 25;
    }

    public RiskFactor evaluate(RiskInput in) {
        if (!in.isCod()) {
            return new RiskFactor(CODE, 0, maxPoints(), "prepaid");
        }
        double ratio = Math.min(1.0, in.codAmount().amount().divide(high, MathContext.DECIMAL64).doubleValue());
        int points = 5 + (int) Math.round(ratio * 20);
        return new RiskFactor(CODE, points, maxPoints(), "COD " + in.codAmount());
    }
}
