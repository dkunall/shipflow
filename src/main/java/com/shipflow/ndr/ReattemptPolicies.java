package com.shipflow.ndr;

import com.shipflow.config.ShipFlowProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Built-in, configurable re-attempt policies (shipflow.ndr.*). */
public final class ReattemptPolicies {
    private ReattemptPolicies() {
    }

    /** Reasons where re-attempting is pointless (customer refused, outside area) -> RTO immediately. */
    @Component
    @Order(10)
    public static class ImmediateRtoReasons implements ReattemptPolicy {
        private final Set<NdrReason> reasons;

        public ImmediateRtoReasons(ShipFlowProperties props) {
            List<String> configured = props.ndr().immediateRtoReasons();
            Set<NdrReason> s = EnumSet.noneOf(NdrReason.class);
            if (configured != null) {
                configured.forEach(r -> s.add(NdrReason.valueOf(r)));
            }
            this.reasons = s;
        }

        public Optional<NdrDecision> decide(NdrContext ctx) {
            return reasons.contains(ctx.reason())
                    ? Optional.of(new NdrDecision(NdrAction.RTO, ctx.reason() + " is a terminal NDR reason"))
                    : Optional.empty();
        }
    }

    /** Auto-RTO once the configured number of attempts has failed. */
    @Component
    @Order(20)
    public static class MaxAttempts implements ReattemptPolicy {
        private final int max;

        public MaxAttempts(ShipFlowProperties props) {
            this.max = props.ndr().maxAttempts();
        }

        public Optional<NdrDecision> decide(NdrContext ctx) {
            return ctx.attemptNo() >= max
                    ? Optional.of(new NdrDecision(NdrAction.RTO, "attempt " + ctx.attemptNo() + " of max " + max + " failed"))
                    : Optional.empty();
        }
    }
}
