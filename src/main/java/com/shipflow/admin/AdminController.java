package com.shipflow.admin;

import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.carrier.port.SimulatedCarrierNetwork;
import com.shipflow.carrier.resilience.CircuitBreakerRegistry;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.config.ShipFlowProperties;
import com.shipflow.scorecard.ScorecardMath;
import com.shipflow.scorecard.ScorecardRepository;
import com.shipflow.webhook.OutboxRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** ADMIN-only (enforced by URL rule /api/v1/admin/**). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService admin;
    private final SimulatedCarrierNetwork simulator;
    private final CircuitBreakerRegistry breakers;
    private final CarrierDirectory carriers;
    private final ScorecardRepository scorecards;
    private final OutboxRepository outbox;
    private final ShipFlowProperties props;
    private final Clock clock;

    public AdminController(AdminService admin, SimulatedCarrierNetwork simulator, CircuitBreakerRegistry breakers,
                           CarrierDirectory carriers, ScorecardRepository scorecards, OutboxRepository outbox,
                           ShipFlowProperties props, Clock clock) {
        this.admin = admin;
        this.simulator = simulator;
        this.breakers = breakers;
        this.carriers = carriers;
        this.scorecards = scorecards;
        this.outbox = outbox;
        this.props = props;
        this.clock = clock;
    }

    @PostMapping("/merchants")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createMerchant(@Valid @RequestBody AdminRequests.CreateMerchant r) {
        return admin.createMerchant(r);
    }

    @PostMapping("/carriers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCarrier(@Valid @RequestBody AdminRequests.CreateCarrier r) {
        return admin.createCarrier(r);
    }

    @PutMapping("/carriers/{code}/capacity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setCapacity(@PathVariable String code, @Valid @RequestBody AdminRequests.SetCapacity r) {
        admin.setCapacity(code, r);
    }

    @GetMapping("/carriers/{code}/scorecard")
    public Map<String, Object> scorecard(@PathVariable String code, @RequestParam String zone) {
        var c = carriers.requireCode(code);
        LocalDate since = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(props.scorecard().windowDays());
        var t = scorecards.totals(c.id(), zone, since);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("carrier", code);
        m.put("zone", zone);
        m.put("windowDays", props.scorecard().windowDays());
        m.put("raw", t);
        m.put("smoothed", ScorecardMath.scorecard(t.delivered(), t.onTime(), t.firstAttempt(), t.rto(), props.scorecard()));
        m.put("circuit", breakers.forCarrier(code).state());
        return m;
    }

    /** Chaos/demo switch for the simulated 3PL APIs. */
    @PutMapping("/simulator/carriers/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void simulate(@PathVariable String code, @Valid @RequestBody AdminRequests.SimulateCarrier r) {
        carriers.requireCode(code);
        simulator.configure(code, SimulatedCarrierNetwork.Mode.valueOf(r.mode()),
                Duration.ofMillis(r.latencyMs() == null ? 0 : r.latencyMs()));
    }

    @PostMapping("/webhooks/dead-letters/{eventId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void replay(@PathVariable UUID eventId) {
        if (!outbox.replayDead(eventId, clock.instant())) {
            throw ShipFlowException.notFound("dead-lettered event", eventId);
        }
    }
}
