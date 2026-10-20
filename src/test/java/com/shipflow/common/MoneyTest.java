package com.shipflow.common;

import com.shipflow.common.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {
    @Test
    void exactDecimalArithmeticAtScaleTwo() {
        Money a = Money.of("0.10", "AED");
        Money sum = Money.zero("AED");
        for (int i = 0; i < 10; i++) {
            sum = sum.plus(a);
        }
        assertThat(sum).isEqualTo(Money.of("1.00", "AED"));
        assertThat(Money.of("1", "AED")).isEqualTo(Money.of("1.000", "AED"));
        assertThat(Money.of("10.005", "AED").amount()).isEqualByComparingTo("10.01"); // HALF_UP
        assertThat(Money.of("99.99", "SAR").percent(new BigDecimal("1.5"))).isEqualTo(Money.of("1.50", "SAR"));
        assertThat(Money.of("5", "AED").times(3).minus(Money.of("0.01", "AED"))).isEqualTo(Money.of("14.99", "AED"));
    }

    @Test
    void currencySafety() {
        assertThatThrownBy(() -> Money.of("1", "AED").plus(Money.of("1", "SAR"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1", "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Money.of("2", "AED").max(Money.of("3", "AED"))).isEqualTo(Money.of("3", "AED"));
        assertThat(Money.of("0", "AED").isPositive()).isFalse();
    }
}
