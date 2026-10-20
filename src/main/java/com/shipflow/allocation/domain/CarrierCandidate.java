package com.shipflow.allocation.domain;

import com.shipflow.carrier.domain.CarrierInfo;
import com.shipflow.carrier.domain.RateCard;
import com.shipflow.scorecard.CarrierScorecard;

/** A carrier serving the destination zone, with its terms, today's capacity snapshot and its scorecard. */
public record CarrierCandidate(CarrierInfo carrier, int slaDays, boolean codSupported, RateCard rateCard,
                               int capacity, int reserved, CarrierScorecard scorecard) {
    public int remainingCapacity() {
        return Math.max(capacity - reserved, 0);
    }
}
