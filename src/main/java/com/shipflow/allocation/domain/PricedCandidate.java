package com.shipflow.allocation.domain;

import com.shipflow.carrier.domain.RateCard;

public record PricedCandidate(CarrierCandidate candidate, RateCard.Quote quote) {
    public String code() {
        return candidate.carrier().code();
    }
}
