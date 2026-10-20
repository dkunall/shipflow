package com.shipflow.ndr;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Evaluates policies in @Order; first decision wins, default REATTEMPT. */
@Component
public class ReattemptPolicyChain {
    private final List<ReattemptPolicy> policies;

    public ReattemptPolicyChain(List<ReattemptPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    public ReattemptPolicy.NdrDecision decide(NdrContext ctx) {
        for (ReattemptPolicy p : policies) {
            Optional<ReattemptPolicy.NdrDecision> d = p.decide(ctx);
            if (d.isPresent()) {
                return d.get();
            }
        }
        return new ReattemptPolicy.NdrDecision(NdrAction.REATTEMPT, "re-attempt (attempt " + ctx.attemptNo() + ")");
    }
}
