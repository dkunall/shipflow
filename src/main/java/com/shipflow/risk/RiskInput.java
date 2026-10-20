package com.shipflow.risk;

import com.shipflow.common.money.Money;

/** Everything a risk rule may look at (no I/O inside rules: stats are pre-loaded). */
public record RiskInput(String addressLine1, String addressLine2, String landmark, String city, Money codAmount,
                        DeliveryStats pincodeStats, CustomerStats customerStats) {

    public record DeliveryStats(long attempts, long failures) {
        public static final DeliveryStats NONE = new DeliveryStats(0, 0);
    }

    public record CustomerStats(long attempts, long failures, long rto) {
        public static final CustomerStats NONE = new CustomerStats(0, 0, 0);
    }

    public boolean isCod() {
        return codAmount != null && codAmount.isPositive();
    }
}
