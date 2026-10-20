package com.shipflow.scan;

import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.carrier.domain.CarrierInfo;
import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.ndr.NdrReason;
import com.shipflow.ndr.NdrService;
import com.shipflow.observability.ShipFlowMetrics;
import com.shipflow.shipment.application.ShipmentLifecycle;
import com.shipflow.shipment.application.TransitionCause;
import com.shipflow.shipment.domain.Shipment;
import com.shipflow.shipment.domain.ShipmentStateMachine;
import com.shipflow.shipment.domain.ShipmentStatus;
import com.shipflow.shipment.persistence.ShipmentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scan ingestion: idempotent (dedupe on carrier event id), out-of-order safe (event-time + rank rules in
 * {@link ScanPolicy}), and concurrency safe through optimistic locking: every stored scan bumps the shipment's
 * {@code @Version}, so a decision taken on a stale snapshot can never commit - the transaction is retried
 * on the fresh state instead.
 */
@Service
public class ScanIngestionService {
    private static final int MAX_RETRIES = 10;
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(10);

    private final ShipmentRepository shipments;
    private final ScanEventRepository scans;
    private final CarrierDirectory carriers;
    private final ShipmentLifecycle lifecycle;
    private final NdrService ndr;
    private final ShipFlowMetrics metrics;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ScanIngestionService(ShipmentRepository shipments, ScanEventRepository scans, CarrierDirectory carriers,
                                ShipmentLifecycle lifecycle, NdrService ndr, ShipFlowMetrics metrics,
                                EntityManager em, PlatformTransactionManager txm, Clock clock) {
        this.shipments = shipments;
        this.scans = scans;
        this.carriers = carriers;
        this.lifecycle = lifecycle;
        this.ndr = ndr;
        this.metrics = metrics;
        this.em = em;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
    }

    public ScanResult ingest(ScanCommand cmd, String source, String actor) {
        ShipmentStatus target = parseTarget(cmd);
        NdrReason reason = parseReason(cmd, target);
        if (cmd.occurredAt().isAfter(clock.instant().plus(MAX_CLOCK_SKEW))) {
            throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "occurredAt is in the future");
        }
        CarrierInfo carrier = carriers.requireCode(cmd.carrierCode());
        for (int attempt = 1; ; attempt++) {
            try {
                ScanResult r = tx.execute(s -> apply(cmd, carrier, target, reason, source, actor));
                metrics.scan(r.outcome().name(), source);
                return r;
            } catch (OptimisticLockingFailureException | PessimisticLockingFailureException e) {
                if (attempt >= MAX_RETRIES) {
                    throw new ShipFlowException(ErrorCode.CONCURRENT_MODIFICATION,
                            "shipment is being updated concurrently; retry the scan");
                }
                backoff(attempt);
            }
        }
    }

    private ScanResult apply(ScanCommand cmd, CarrierInfo carrier, ShipmentStatus target, NdrReason reason,
                             String source, String actor) {
        Shipment s = (cmd.trackingNumber() != null
                ? shipments.findByTrackingNumber(cmd.trackingNumber())
                : shipments.findByCarrierIdAndCarrierAwb(carrier.id(), cmd.awb()))
                .orElseThrow(() -> ShipFlowException.notFound("shipment",
                        cmd.trackingNumber() != null ? cmd.trackingNumber() : cmd.awb()));
        ScanPolicy.Decision d = ScanPolicy.decide(s.getStatus(), s.getStatusEventAt(), s.getCarrierId(), carrier.id(),
                target, cmd.occurredAt());
        if (!scans.insert(s.getId(), carrier.id(), cmd, source, d.outcome(), d.detail(), clock.instant())) {
            return new ScanResult(cmd.carrierEventId(), ScanOutcome.DUPLICATE, s.getStatus().name(), List.of(),
                    "event already received (first outcome: " + scans.existingOutcome(carrier.id(), cmd.carrierEventId()) + ")");
        }
        List<String> steps = new ArrayList<>();
        if (d.outcome() == ScanOutcome.APPLIED) {
            for (ShipmentStatus step : d.path()) {
                boolean inferred = step != target;
                lifecycle.transition(s, step, TransitionCause.scan(actor, cmd.occurredAt(), inferred, cmd.location(),
                        step == ShipmentStatus.DELIVERY_FAILED ? reason.name() : null));
                steps.add(step.name());
                if (step == ShipmentStatus.DELIVERY_FAILED) {
                    var action = ndr.onFailedAttempt(s, reason, cmd.occurredAt());
                    if (action == com.shipflow.ndr.NdrAction.RTO) {
                        steps.add(ShipmentStatus.RTO_INITIATED.name());
                    }
                } else if (step == ShipmentStatus.DELIVERED) {
                    ndr.onDelivered(s, cmd.occurredAt());
                }
            }
        } else {
            // not applied, but the decision was taken on this version: make sure it is still current at commit
            em.lock(s, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        }
        shipments.flush();
        return new ScanResult(cmd.carrierEventId(), d.outcome(), s.getStatus().name(), steps, d.detail());
    }

    private static ShipmentStatus parseTarget(ScanCommand cmd) {
        try {
            ShipmentStatus t = ShipmentStatus.valueOf(cmd.eventCode());
            if (!ShipmentStateMachine.isScanTarget(t)) {
                throw new IllegalArgumentException();
            }
            return t;
        } catch (IllegalArgumentException e) {
            throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "unsupported eventCode " + cmd.eventCode());
        }
    }

    private static NdrReason parseReason(ScanCommand cmd, ShipmentStatus target) {
        if (target != ShipmentStatus.DELIVERY_FAILED) {
            return null;
        }
        if (cmd.ndrReason() == null) {
            throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "ndrReason is required for DELIVERY_FAILED");
        }
        try {
            return NdrReason.valueOf(cmd.ndrReason());
        } catch (IllegalArgumentException e) {
            throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "unknown ndrReason " + cmd.ndrReason());
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 5L * attempt + 2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
