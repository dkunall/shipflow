package com.shipflow.ndr;

import java.util.Optional;

/**
 * Chain-of-responsibility link deciding what happens after a failed attempt. Return empty to defer to the next
 * policy; the chain defaults to REATTEMPT when no policy decides.
 */
public interface ReattemptPolicy {
    Optional<NdrDecision> decide(NdrContext context);

    record NdrDecision(NdrAction action, String rationale) {
    }
}
