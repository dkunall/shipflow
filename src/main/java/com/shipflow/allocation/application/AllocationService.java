package com.shipflow.allocation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipflow.allocation.domain.AllocationContext;
import com.shipflow.allocation.domain.AllocationEngine;
import com.shipflow.allocation.domain.AllocationPlan;
import com.shipflow.carrier.capacity.CapacityRepository;
import com.shipflow.common.money.Money;
import com.shipflow.config.ShipFlowProperties;
import com.shipflow.edd.EddService;
import com.shipflow.edd.PromiseDateCalculator;
import com.shipflow.network.HubInfo;
import com.shipflow.network.NetworkDirectory;
import com.shipflow.observability.ShipFlowMetrics;
import com.shipflow.shipment.application.ShipmentLifecycle;
import com.shipflow.shipment.application.TransitionCause;
import com.shipflow.shipment.domain.Shipment;
import com.shipflow.shipment.domain.ShipmentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates one allocation inside the caller's (short) transaction:
 * load candidates (1 query) -> pure engine ranks them -> atomically reserve capacity on the best one that still
 * has room (falls through on lost races) -> store the explainable decision -> move the shipment to ALLOCATED.
 * No external calls here; carrier booking happens after commit (see BookingOrchestrator).
 */
@Service
public class AllocationService {
    public record Result(Optional<Long> carrierId, long decisionId, String summary) {
    }

    private final CandidateRepository candidates;
    private final AllocationEngine engine;
    private final CapacityRepository capacity;
    private final AllocationDecisionRepository decisions;
    private final EddService edd;
    private final NetworkDirectory network;
    private final ShipmentLifecycle lifecycle;
    private final ShipFlowMetrics metrics;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int scorecardWindowDays;

    public AllocationService(CandidateRepository candidates, AllocationEngine engine, CapacityRepository capacity,
                             AllocationDecisionRepository decisions, EddService edd, NetworkDirectory network,
                             ShipmentLifecycle lifecycle, ShipFlowMetrics metrics, ObjectMapper mapper, Clock clock,
                             ShipFlowProperties props) {
        this.candidates = candidates;
        this.engine = engine;
        this.capacity = capacity;
        this.decisions = decisions;
        this.edd = edd;
        this.network = network;
        this.lifecycle = lifecycle;
        this.metrics = metrics;
        this.mapper = mapper;
        this.clock = clock;
        this.scorecardWindowDays = props.scorecard().windowDays();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result allocate(Shipment s, Set<Long> excludedCarrierIds, String actor, String trigger) {
        long started = System.nanoTime();
        Instant now = clock.instant();
        HubInfo origin = network.hub(s.getOriginHubId()).orElseThrow();
        LocalDate serviceDate = edd.dispatchDate(origin, now);
        Money cod = s.isCod() ? new Money(s.getCodAmount(), s.getCurrency()) : null;
        AllocationContext ctx = new AllocationContext(s.getId(), s.getZoneCode(), s.getWeightG(), cod, serviceDate,
                s.getRiskBand(), Set.copyOf(excludedCarrierIds));
        LocalDate since = now.atZone(origin.zone()).toLocalDate().minusDays(scorecardWindowDays);
        AllocationPlan plan = engine.plan(ctx, candidates.findCandidates(s.getZoneCode(), serviceDate, since));

        AllocationPlan.ScoredCandidate chosen = null;
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (AllocationPlan.ScoredCandidate sc : plan.ranked()) {
            long carrierId = sc.priced().candidate().carrier().id();
            if (capacity.tryReserve(carrierId, s.getZoneCode(), serviceDate)) {
                attempts.add(attempt(sc.code(), "RESERVED"));
                chosen = sc;
                break;
            }
            attempts.add(attempt(sc.code(), "CAPACITY_EXHAUSTED_ON_RESERVE"));
        }
        String chosenCode = chosen == null ? null : chosen.code();
        String explanation = json(AllocationExplanation.build(plan, chosenCode, attempts, trigger));
        Result result;
        if (chosen != null) {
            var cand = chosen.priced().candidate();
            PromiseDateCalculator.Result promise = edd.promise(origin, s.getCountryCode(), cand.carrier().id(),
                    s.getZoneCode(), cand.slaDays(), now);
            boolean reallocation = s.getStatus() == ShipmentStatus.ALLOCATED;
            s.assignCarrier(cand.carrier().id(), chosen.priced().quote().total().amount(), promise.dispatchDate(),
                    promise.promisedDate(), edd.explain(promise, cand.slaDays()), now);
            long id = decisions.insert(s.getId(), cand.carrier().id(), s.getZoneCode(), serviceDate, "RESERVED",
                    chosen.total(), explanation);
            if (reallocation) {
                lifecycle.note(s, actor, "re-allocated to " + chosenCode + " (" + trigger + ")");
            } else {
                lifecycle.transition(s, ShipmentStatus.ALLOCATED, TransitionCause.system(actor,
                        "allocated to " + chosenCode + " (" + trigger + ")"));
            }
            metrics.allocation("allocated", chosenCode);
            result = new Result(Optional.of(cand.carrier().id()), id, chosenCode);
        } else {
            long id = decisions.insert(s.getId(), null, s.getZoneCode(), serviceDate, "UNALLOCATED", null, explanation);
            if (s.getStatus() == ShipmentStatus.ALLOCATED) {
                lifecycle.transition(s, ShipmentStatus.CREATED, TransitionCause.system(actor,
                        "no carrier available after " + trigger));
            }
            metrics.allocation("unallocated", "none");
            result = new Result(Optional.empty(), id, plan.ranked().isEmpty() ? "NO_ELIGIBLE_CARRIER" : "NO_CAPACITY");
        }
        metrics.allocationLatency(Duration.ofNanos(System.nanoTime() - started));
        return result;
    }

    /** Gives the shipment's reserved capacity back exactly once (guarded by the decision row flip). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean releaseReservation(Shipment s, String reason) {
        return decisions.release(s.getId(), reason, clock.instant())
                .map(r -> capacity.release(r.carrierId(), r.zone(), r.serviceDate()))
                .orElse(false);
    }

    private static Map<String, Object> attempt(String carrier, String result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("carrier", carrier);
        m.put("result", result);
        return m;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
