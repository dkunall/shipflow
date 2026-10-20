package com.shipflow.ndr;

import com.shipflow.support.TestProps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReattemptPolicyChainTest {
    private final ReattemptPolicyChain chain = new ReattemptPolicyChain(List.of(
            new ReattemptPolicies.ImmediateRtoReasons(TestProps.defaults()),
            new ReattemptPolicies.MaxAttempts(TestProps.defaults())));

    @Test
    void reattemptsUntilMaxThenRto() {
        assertThat(chain.decide(new NdrContext(1, NdrReason.CUSTOMER_UNAVAILABLE, true, "LOW")).action()).isEqualTo(NdrAction.REATTEMPT);
        assertThat(chain.decide(new NdrContext(2, NdrReason.ADDRESS_INCOMPLETE, false, "LOW")).action()).isEqualTo(NdrAction.REATTEMPT);
        var d = chain.decide(new NdrContext(3, NdrReason.CUSTOMER_UNAVAILABLE, true, "LOW"));
        assertThat(d.action()).isEqualTo(NdrAction.RTO);
        assertThat(d.rationale()).contains("max 3");
    }

    @Test
    void refusalIsImmediateRtoAndFirstMatchingPolicyWins() {
        var d = chain.decide(new NdrContext(1, NdrReason.CUSTOMER_REFUSED, true, "LOW"));
        assertThat(d.action()).isEqualTo(NdrAction.RTO);
        assertThat(d.rationale()).contains("CUSTOMER_REFUSED");
        assertThat(chain.decide(new NdrContext(1, NdrReason.OUT_OF_DELIVERY_AREA, false, "LOW")).action()).isEqualTo(NdrAction.RTO);
    }

    @Test
    void customPolicyCanBePluggedInFront() {
        ReattemptPolicy vip = ctx -> ctx.cod() ? java.util.Optional.empty()
                : java.util.Optional.of(new ReattemptPolicy.NdrDecision(NdrAction.REATTEMPT, "prepaid: always retry"));
        var custom = new ReattemptPolicyChain(List.of(vip, new ReattemptPolicies.MaxAttempts(TestProps.defaults())));
        assertThat(custom.decide(new NdrContext(5, NdrReason.CUSTOMER_UNAVAILABLE, false, "LOW")).action()).isEqualTo(NdrAction.REATTEMPT);
        assertThat(custom.decide(new NdrContext(5, NdrReason.CUSTOMER_UNAVAILABLE, true, "LOW")).action()).isEqualTo(NdrAction.RTO);
    }
}
