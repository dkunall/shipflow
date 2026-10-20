package com.shipflow.carrier.domain;

import java.math.BigDecimal;

public record RateSlab(int upToGrams, BigDecimal price) {
}
