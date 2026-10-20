package com.shipflow.allocation.domain;

import java.math.BigDecimal;
import java.util.List;

/** The eligible (filtered, priced) candidates; gives rules the min/max needed for normalisation. */
public record CandidatePool(List<PricedCandidate> candidates) {
    public BigDecimal minPrice() {
        return candidates.stream().map(c -> c.quote().total().amount()).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    public BigDecimal maxPrice() {
        return candidates.stream().map(c -> c.quote().total().amount()).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    public int minSla() {
        return candidates.stream().mapToInt(c -> c.candidate().slaDays()).min().orElse(0);
    }

    public int maxSla() {
        return candidates.stream().mapToInt(c -> c.candidate().slaDays()).max().orElse(0);
    }
}
