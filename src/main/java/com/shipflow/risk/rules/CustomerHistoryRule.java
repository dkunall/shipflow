package com.shipflow.risk.rules;

import com.shipflow.risk.RiskFactor;
import com.shipflow.risk.RiskInput;
import com.shipflow.risk.RiskRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Consignee's past behaviour, keyed by phone hash (max 20): 5 per failed attempt, 10 per RTO, capped. */
@Component
@Order(40)
public class CustomerHistoryRule implements RiskRule {
    public static final String CODE = "CUSTOMER_HISTORY";

    public String code() {
        return CODE;
    }

    public int maxPoints() {
        return 20;
    }

    public RiskFactor evaluate(RiskInput in) {
        var c = in.customerStats() == null ? RiskInput.CustomerStats.NONE : in.customerStats();
        if (c.attempts() == 0 && c.rto() == 0) {
            return new RiskFactor(CODE, 0, maxPoints(), "no history");
        }
        long points = Math.min(maxPoints(), c.failures() * 5 + c.rto() * 10);
        return new RiskFactor(CODE, (int) points, maxPoints(),
                c.failures() + " failed attempt(s), " + c.rto() + " RTO(s) in " + c.attempts() + " attempt(s)");
    }
}
