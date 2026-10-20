package com.shipflow.risk;

import com.shipflow.common.money.Money;
import com.shipflow.risk.rules.AddressQualityRule;
import com.shipflow.risk.rules.CodAmountRule;
import com.shipflow.risk.rules.CustomerHistoryRule;
import com.shipflow.risk.rules.PincodeFailureRateRule;
import com.shipflow.support.TestProps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskEngineTest {
    private final RiskEngine engine = new RiskEngine(List.of(new AddressQualityRule(), new PincodeFailureRateRule(),
            new CodAmountRule(TestProps.defaults()), new CustomerHistoryRule()), TestProps.defaults());

    private static RiskInput input(String line1, String landmark, Money cod, RiskInput.DeliveryStats area,
                                   RiskInput.CustomerStats customer) {
        return new RiskInput(line1, null, landmark, "Dubai", cod, area, customer);
    }

    @Test
    void cleanPrepaidOrderIsLowRiskWithNoActions() {
        RiskAssessment a = engine.assess(input("Flat 1204, Marina Gate Tower 2", "opposite tram stop", null,
                new RiskInput.DeliveryStats(200, 10), RiskInput.CustomerStats.NONE));
        assertThat(a.band()).isEqualTo("LOW");
        assertThat(a.actions()).isEmpty();
        assertThat(a.factors()).extracting(RiskFactor::rule)
                .containsExactly("ADDRESS_QUALITY", "PINCODE_FAILURE_RATE", "COD_AMOUNT", "CUSTOMER_HISTORY");
        assertThat(a.score()).isEqualTo(a.factors().stream().mapToInt(RiskFactor::points).sum());
    }

    @Test
    void weakAddressIsExplainedFactorByFactor() {
        RiskFactor f = new AddressQualityRule().evaluate(input("near mosque", null, null, null, null));
        assertThat(f.points()).isEqualTo(10 + 8 + 4);
        assertThat(f.reason()).contains("no building/flat number", "shorter than 20", "no landmark");
    }

    @Test
    void codAmountScalesUpToCap() {
        CodAmountRule r = new CodAmountRule(TestProps.defaults());
        assertThat(r.evaluate(input("x", null, null, null, null)).points()).isZero();
        assertThat(r.evaluate(input("x", null, Money.of("250", "AED"), null, null)).points()).isEqualTo(15);
        assertThat(r.evaluate(input("x", null, Money.of("5000", "AED"), null, null)).points()).isEqualTo(25);
    }

    @Test
    void areaFailureRateIsSmoothedSoOneBadAttemptIsNotAlarming() {
        PincodeFailureRateRule r = new PincodeFailureRateRule();
        int one = r.evaluate(input("x", null, null, new RiskInput.DeliveryStats(1, 1), null)).points();
        int many = r.evaluate(input("x", null, null, new RiskInput.DeliveryStats(100, 50), null)).points();
        assertThat(one).isLessThan(10);
        assertThat(many).isGreaterThanOrEqualTo(20);
    }

    @Test
    void highRiskCodGetsOtpAndPrepaidNudge() {
        RiskAssessment a = engine.assess(input("deliver here", null, Money.of("900", "AED"),
                new RiskInput.DeliveryStats(50, 30), new RiskInput.CustomerStats(4, 2, 1)));
        assertThat(a.band()).isEqualTo("HIGH");
        assertThat(a.actions()).contains(RiskAction.REQUIRE_DELIVERY_OTP, RiskAction.PREPAID_CONVERSION_NUDGE);
        assertThat(a.score()).isLessThanOrEqualTo(100);
    }

    @Test
    void mediumCodRequiresOtpOnly() {
        RiskAssessment a = engine.assess(input("Villa 3, Street 12, Al Barsha", "school", Money.of("500", "AED"),
                new RiskInput.DeliveryStats(100, 30), RiskInput.CustomerStats.NONE));
        assertThat(a.band()).isEqualTo("MEDIUM");
        assertThat(a.actions()).containsExactly(RiskAction.REQUIRE_DELIVERY_OTP);
    }
}
