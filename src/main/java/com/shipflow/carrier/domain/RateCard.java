package com.shipflow.carrier.domain;

import com.shipflow.common.money.Money;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Weight-slab rate card of one carrier for one zone.
 * <ul>
 *   <li>freight = price of the smallest slab covering the weight; above the largest slab (up to maxWeight):
 *       largest slab + ceil(excess / 500 g) * extraPer500g;</li>
 *   <li>COD fee = max(codAmount * codFeePercent / 100, codFeeMin), only for COD shipments.</li>
 * </ul>
 */
public record RateCard(String currency, List<RateSlab> slabs, BigDecimal extraPer500g, int maxWeightGrams,
                       BigDecimal codFeePercent, BigDecimal codFeeMin) {

    public RateCard {
        slabs = slabs.stream().sorted(Comparator.comparingInt(RateSlab::upToGrams)).toList();
    }

    public record Quote(Money freight, Money codFee, Money total) {
    }

    public Optional<Quote> quote(int weightGrams, Money codAmount) {
        if (weightGrams <= 0 || weightGrams > maxWeightGrams || slabs.isEmpty()) {
            return Optional.empty();
        }
        Money freight = null;
        for (RateSlab s : slabs) {
            if (weightGrams <= s.upToGrams()) {
                freight = new Money(s.price(), currency);
                break;
            }
        }
        if (freight == null) {
            RateSlab last = slabs.get(slabs.size() - 1);
            long extraUnits = (weightGrams - last.upToGrams() + 499L) / 500L;
            freight = new Money(last.price(), currency).plus(new Money(extraPer500g, currency).times(extraUnits));
        }
        Money codFee = Money.zero(currency);
        if (codAmount != null && codAmount.isPositive()) {
            if (!codAmount.currency().equals(currency)) {
                return Optional.empty();
            }
            codFee = codAmount.percent(codFeePercent).max(new Money(codFeeMin, currency));
        }
        return Optional.of(new Quote(freight, codFee, freight.plus(codFee)));
    }
}
