package com.shipflow.shipment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.allocation.application.AllocationDecisionRepository;
import com.shipflow.booking.application.ShipmentCommandService;
import com.shipflow.booking.carrier.CarrierBookingRepository;
import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.carrier.domain.CarrierInfo;
import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.common.web.PageResponse;
import com.shipflow.scan.ScanEventRepository;
import com.shipflow.security.Actor;
import com.shipflow.shipment.application.ShipmentViews;
import com.shipflow.shipment.domain.ShipmentStatus;
import com.shipflow.shipment.persistence.ShipmentHistoryRepository;
import com.shipflow.shipment.persistence.ShipmentQueryRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shipments")
@Validated
public class ShipmentController {
    public record ReasonRequest(@Size(max = 200) String reason) {
    }

    public record ReallocateRequest(@Size(max = 200) String reason, @Size(max = 20) List<String> excludeCarriers) {
    }

    private final ShipmentCommandService commands;
    private final ShipmentQueryRepository queries;
    private final ShipmentViews views;
    private final ShipmentHistoryRepository history;
    private final ScanEventRepository scans;
    private final AllocationDecisionRepository decisions;
    private final CarrierBookingRepository bookings;
    private final CarrierDirectory carriers;

    public ShipmentController(ShipmentCommandService commands, ShipmentQueryRepository queries, ShipmentViews views,
                              ShipmentHistoryRepository history, ScanEventRepository scans,
                              AllocationDecisionRepository decisions, CarrierBookingRepository bookings,
                              CarrierDirectory carriers) {
        this.commands = commands;
        this.queries = queries;
        this.views = views;
        this.history = history;
        this.scans = scans;
        this.decisions = decisions;
        this.bookings = bookings;
        this.carriers = carriers;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MERCHANT','OPS','ADMIN')")
    public PageResponse<ShipmentSummary> list(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String carrier,
                                              @RequestParam(required = false) Instant createdFrom,
                                              @RequestParam(required = false) Instant createdTo,
                                              @RequestParam(required = false) UUID merchantId,
                                              @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
                                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Actor actor = Actor.current();
        UUID scope = actor.isInternal() ? merchantId : actor.requireMerchant();
        if (!actor.isInternal() && merchantId != null && !merchantId.equals(scope)) {
            throw ShipFlowException.forbidden("cannot list another merchant's shipments");
        }
        if (status != null) {
            try {
                ShipmentStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "unknown status " + status);
            }
        }
        var filter = new ShipmentQueryRepository.Filter(scope, status, carrier, createdFrom, createdTo);
        return PageResponse.of(queries.list(filter, page, size), page, size, queries.count(filter));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MERCHANT','OPS','ADMIN')")
    public ShipmentView get(@PathVariable UUID id) {
        return views.of(commands.load(Actor.current(), id));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('MERCHANT','OPS','ADMIN')")
    public Map<String, Object> history(@PathVariable UUID id) {
        commands.load(Actor.current(), id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statusHistory", history.findByShipment(id));
        m.put("scans", scans.findByShipment(id));
        return m;
    }

    /** The explainable allocation trail: every decision (incl. failovers) with per-rule scores + bookings. */
    @GetMapping("/{id}/allocation")
    @PreAuthorize("hasAnyRole('MERCHANT','OPS','ADMIN')")
    public Map<String, Object> allocation(@PathVariable UUID id) {
        commands.load(Actor.current(), id);
        List<Map<String, Object>> ds = decisions.findByShipment(id).stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("outcome", d.outcome());
            m.put("carrier", d.carrierId() == null ? null : carriers.byId(d.carrierId()).map(CarrierInfo::code).orElse(null));
            m.put("serviceDate", d.serviceDate());
            m.put("score", d.score());
            m.put("releaseReason", d.releaseReason());
            m.put("createdAt", d.createdAt());
            JsonNode explanation = views.tree(d.explanationJson());
            m.put("explanation", explanation);
            return m;
        }).toList();
        List<Map<String, Object>> bs = bookings.byShipment(id).stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("carrier", carriers.byId(b.carrierId()).map(CarrierInfo::code).orElse(null));
            m.put("bookingRef", b.bookingRef());
            m.put("status", b.status());
            m.put("awb", b.awb());
            m.put("attempts", b.attempts());
            return m;
        }).toList();
        return Map.of("decisions", ds, "carrierBookings", bs);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('MERCHANT','OPS','ADMIN')")
    public ShipmentView cancel(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest body) {
        return views.of(commands.cancel(Actor.current(), id, body == null ? null : body.reason()));
    }

    @PostMapping("/{id}/reallocate")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public ShipmentView reallocate(@PathVariable UUID id, @Valid @RequestBody(required = false) ReallocateRequest body) {
        return views.of(commands.reallocate(Actor.current(), id,
                body == null || body.excludeCarriers() == null ? Set.of() : Set.copyOf(body.excludeCarriers()),
                body == null ? null : body.reason()));
    }

    @PostMapping("/{id}/rto")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public ShipmentView rto(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest body) {
        return views.of(commands.initiateRto(Actor.current(), id, body == null ? null : body.reason()));
    }
}
