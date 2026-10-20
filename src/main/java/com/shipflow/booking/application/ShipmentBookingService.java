package com.shipflow.booking.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipflow.allocation.application.AllocationService;
import com.shipflow.booking.api.CreateShipmentRequest;
import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.common.money.Money;
import com.shipflow.network.HubInfo;
import com.shipflow.network.NetworkDirectory;
import com.shipflow.network.PincodeInfo;
import com.shipflow.observability.ShipFlowMetrics;
import com.shipflow.risk.RiskAssessment;
import com.shipflow.risk.RiskEngine;
import com.shipflow.risk.RiskInput;
import com.shipflow.risk.RiskStatsRepository;
import com.shipflow.security.HmacSigner;
import com.shipflow.shipment.application.ShipmentLifecycle;
import com.shipflow.shipment.domain.Shipment;
import com.shipflow.shipment.persistence.ShipmentRepository;
import com.shipflow.webhook.OutboxWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Books a shipment: validate serviceability -> score delivery risk -> persist (CREATED) -> allocate a carrier
 * (capacity reserved atomically) - all in ONE short transaction (joined with the idempotency marker).
 * The external carrier booking happens after commit (BookingOrchestrator).
 */
@Service
public class ShipmentBookingService {
    public record Booked(UUID shipmentId, String allocationSummary) {
    }

    private final NetworkDirectory network;
    private final RiskEngine risk;
    private final RiskStatsRepository riskStats;
    private final ShipmentRepository shipments;
    private final ShipmentLifecycle lifecycle;
    private final AllocationService allocation;
    private final OutboxWriter outbox;
    private final ShipFlowMetrics metrics;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ShipmentBookingService(NetworkDirectory network, RiskEngine risk, RiskStatsRepository riskStats,
                                  ShipmentRepository shipments, ShipmentLifecycle lifecycle,
                                  AllocationService allocation, OutboxWriter outbox, ShipFlowMetrics metrics,
                                  ObjectMapper mapper, Clock clock) {
        this.network = network;
        this.risk = risk;
        this.riskStats = riskStats;
        this.shipments = shipments;
        this.lifecycle = lifecycle;
        this.allocation = allocation;
        this.outbox = outbox;
        this.metrics = metrics;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public Booked createAndAllocate(UUID merchantId, CreateShipmentRequest req, String source, String actor) {
        return createAndAllocate(merchantId, req, source, actor, AllocationService.Reservation.FIRST);
    }

    @Transactional
    public Booked createAndAllocate(UUID merchantId, CreateShipmentRequest req, String source, String actor,
                                    AllocationService.Reservation reservation) {
        PincodeInfo pin = network.pincode(req.consignee().pincode()).orElseThrow(() -> new ShipFlowException(
                ErrorCode.UNSERVICEABLE, "destination pincode " + req.consignee().pincode() + " is not serviceable"));
        HubInfo origin = network.hub(req.originHubCode()).orElseThrow(() -> new ShipFlowException(
                ErrorCode.UNPROCESSABLE, "unknown origin hub " + req.originHubCode()));
        String currency = network.country(pin.countryCode()).currency();
        if (!currency.equals(req.declaredValue().currency())) {
            throw new ShipFlowException(ErrorCode.UNPROCESSABLE, "destination country " + pin.countryCode()
                    + " uses " + currency + "; got " + req.declaredValue().currency());
        }
        Money cod = req.codAmount() == null ? null : new Money(req.codAmount().amount(), req.codAmount().currency());
        String phoneHash = HmacSigner.sha256Hex(normalisePhone(req.consignee().phone()));
        RiskAssessment assessment = risk.assess(new RiskInput(req.consignee().addressLine1(),
                req.consignee().addressLine2(), req.consignee().landmark(), req.consignee().city(), cod,
                riskStats.pincode(pin.code()), riskStats.customer(phoneHash)));

        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        var c = req.consignee();
        Shipment s = Shipment.book(new Shipment.NewShipment(id, merchantId, req.merchantOrderRef(),
                TrackingNumbers.next(), source, origin.id(), pin.hubId(), pin.code(), pin.zoneCode(),
                pin.countryCode(), c.name(), c.phone(), phoneHash, c.addressLine1(), c.addressLine2(), c.landmark(),
                c.city(), c.lat() != null ? c.lat() : pin.lat(), c.lng() != null ? c.lng() : pin.lng(),
                req.weightGrams(), req.paymentMode(), cod == null ? null : cod.amount(), currency,
                req.declaredValue().amount(), assessment.score(), assessment.band(), json(assessment)), now);
        try {
            shipments.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            String msg = String.valueOf(e.getMostSpecificCause().getMessage());
            if (msg.contains("uq_shipment_merchant_order")) {
                throw new ShipFlowException(ErrorCode.DUPLICATE_ORDER,
                        "merchantOrderRef " + req.merchantOrderRef() + " already booked");
            }
            throw e;
        }
        lifecycle.created(s, actor);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shipmentId", id.toString());
        data.put("trackingNumber", s.getTrackingNumber());
        data.put("merchantOrderRef", s.getMerchantOrderRef());
        data.put("riskScore", assessment.score());
        data.put("riskBand", assessment.band());
        data.put("riskActions", assessment.actions());
        outbox.write(merchantId, id, "shipment.created", data);
        var result = allocation.allocate(s, Set.of(), actor, "BOOKING", reservation);
        shipments.flush();
        metrics.shipmentBooked(source);
        return new Booked(id, result.summary());
    }

    static String normalisePhone(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
