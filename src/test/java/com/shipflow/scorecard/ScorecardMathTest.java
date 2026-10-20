package com.shipflow.scorecard;

import com.shipflow.support.TestProps;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ScorecardMathTest {
    @Test
    void noDataReturnsPrior() {
        assertThat(ScorecardMath.smoothed(0, 0, 0.85, 20)).isEqualTo(0.85);
    }

    @Test
    void bayesianSmoothingPullsSmallSamplesToPrior() {
        // 1/1 on time with prior 0.85 weight 20 => (1 + 17) / 21
        assertThat(ScorecardMath.smoothed(1, 1, 0.85, 20)).isCloseTo(18.0 / 21, within(1e-12));
        // large samples dominate
        assertThat(ScorecardMath.smoothed(500, 1000, 0.85, 20)).isCloseTo((500 + 17) / 1020.0, within(1e-12));
    }

    @Test
    void scorecardRtoRateUsesAllClosedShipments() {
        CarrierScorecard c = ScorecardMath.scorecard(90, 80, 70, 10, TestProps.scorecard());
        assertThat(c.onTimeRate()).isCloseTo((80 + 0.85 * 20) / 110, within(1e-12));
        assertThat(c.firstAttemptRate()).isCloseTo((70 + 0.80 * 20) / 110, within(1e-12));
        assertThat(c.rtoRate()).isCloseTo((10 + 0.08 * 20) / 120, within(1e-12));
    }

    @Test
    void rejectsImpossibleCounts() {
        assertThatThrownBy(() -> ScorecardMath.smoothed(5, 4, 0.5, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
