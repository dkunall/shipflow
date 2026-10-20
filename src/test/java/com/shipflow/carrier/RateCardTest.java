package com.shipflow.carrier;

import com.shipflow.carrier.domain.RateCard;
import com.shipflow.carrier.domain.RateSlab;
import com.shipflow.common.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateCardTest {
    private final RateCard card = new RateCard("AED", List.of(new RateSlab(500, new BigDecimal("8.00")),
            new RateSlab(2000, new BigDecimal("12.00")), new RateSlab(5000, new BigDecimal("18.00"))),
            new BigDecimal("2.50"), 30_000, new BigDecimal("2.0"), new BigDecimal("5.00"));

    @Test
    void picksSmallestSlabThatFits() {
        assertThat(card.quote(500, null).orElseThrow().freight()).isEqualTo(Money.of("8.00", "AED"));
        assertThat(card.quote(501, null).orElseThrow().freight()).isEqualTo(Money.of("12.00", "AED"));
        assertThat(card.quote(5000, null).orElseThrow().freight()).isEqualTo(Money.of("18.00", "AED"));
    }

    @Test
    void chargesPerStartedHalfKiloAboveTopSlab() {
        // 6201 g = 1201 g above 5 kg => 3 started 500 g blocks
        assertThat(card.quote(6201, null).orElseThrow().freight()).isEqualTo(Money.of("25.50", "AED"));
        assertThat(card.quote(5500, null).orElseThrow().freight()).isEqualTo(Money.of("20.50", "AED"));
    }

    @Test
    void codFeeIsMaxOfPercentAndMinimum() {
        var small = card.quote(400, Money.of("100.00", "AED")).orElseThrow();
        assertThat(small.codFee()).isEqualTo(Money.of("5.00", "AED")); // 2% = 2.00 < min 5.00
        var big = card.quote(400, Money.of("1234.56", "AED")).orElseThrow();
        assertThat(big.codFee()).isEqualTo(Money.of("24.69", "AED")); // 24.6912 HALF_UP
        assertThat(big.total()).isEqualTo(Money.of("32.69", "AED"));
    }

    @Test
    void unserviceableWeightsAndCurrencyMismatch() {
        assertThat(card.quote(0, null)).isEmpty();
        assertThat(card.quote(30_001, null)).isEmpty();
        assertThat(card.quote(400, Money.of("10", "SAR"))).isEmpty();
    }
}
