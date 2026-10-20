package com.shipflow.allocation.domain;

import com.shipflow.common.money.Money;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** Facts about the shipment being allocated (input to filters and scoring rules). */
public record AllocationContext(UUID shipmentId, String zoneCode, int weightGrams, Money codAmount,
                                LocalDate serviceDate, String riskBand, Set<Long> excludedCarrierIds) {
    public boolean isCod() {
        return codAmount != null && codAmount.isPositive();
    }

    public boolean isHighRisk() {
        return "HIGH".equals(riskBand);
    }
}
