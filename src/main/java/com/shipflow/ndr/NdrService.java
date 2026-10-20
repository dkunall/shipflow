package com.shipflow.ndr;

import com.shipflow.observability.ShipFlowMetrics;
import com.shipflow.shipment.application.ShipmentLifecycle;
import com.shipflow.shipment.application.TransitionCause;
import com.shipflow.shipment.domain.Shipment;
import com.shipflow.shipment.domain.ShipmentStatus;
import com.shipflow.webhook.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** NDR automation: record the attempt, consult the policy chain, auto-RTO when it says so. */
@Service
public class NdrService {
    private final ReattemptPolicyChain policies;
    private final DeliveryAttemptRepository attempts;
    private final ShipmentLifecycle lifecycle;
    private final OutboxWriter outbox;
    private final ShipFlowMetrics metrics;
    private final Clock clock;

    public NdrService(ReattemptPolicyChain policies, DeliveryAttemptRepository attempts, ShipmentLifecycle lifecycle,
                      OutboxWriter outbox, ShipFlowMetrics metrics, Clock clock) {
        this.policies = policies;
        this.attempts = attempts;
        this.lifecycle = lifecycle;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public NdrAction onFailedAttempt(Shipment s, NdrReason reason, Instant occurredAt) {
        s.recordNdr(reason.name(), clock.instant());
        var decision = policies.decide(new NdrContext(s.getAttemptCount(), reason, s.isCod(), s.getRiskBand()));
        attempts.record(s.getId(), Math.max(1, s.getAttemptCount()), "FAILED", reason.name(), decision.action().name(),
                s.getCarrierId(), occurredAt);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shipmentId", s.getId().toString());
        data.put("trackingNumber", s.getTrackingNumber());
        data.put("attempt", s.getAttemptCount());
        data.put("reason", reason.name());
        data.put("action", decision.action().name());
        data.put("rationale", decision.rationale());
        outbox.write(s.getMerchantId(), s.getId(), "shipment.ndr", data);
        metrics.ndr(reason.name(), decision.action().name());
        if (decision.action() == NdrAction.RTO) {
            lifecycle.transition(s, ShipmentStatus.RTO_INITIATED,
                    TransitionCause.system("system:ndr-automation", "auto-RTO: " + decision.rationale()));
        }
        return decision.action();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onDelivered(Shipment s, Instant occurredAt) {
        attempts.record(s.getId(), Math.max(1, s.getAttemptCount()), "DELIVERED", null, "NONE", s.getCarrierId(),
                occurredAt);
    }
}
