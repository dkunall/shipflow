package com.shipflow.shipment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.shipment.api.ShipmentView;
import com.shipflow.shipment.domain.Shipment;
import org.springframework.stereotype.Component;

/** Entity -> API DTO mapping (entities never leave the application layer). */
@Component
public class ShipmentViews {
    private final CarrierDirectory carriers;
    private final ObjectMapper mapper;

    public ShipmentViews(CarrierDirectory carriers, ObjectMapper mapper) {
        this.carriers = carriers;
        this.mapper = mapper;
    }

    public ShipmentView of(Shipment s) {
        ShipmentView.CarrierRef carrier = s.getCarrierId() == null ? null : carriers.byId(s.getCarrierId())
                .map(c -> new ShipmentView.CarrierRef(c.code(), c.name(), c.kind().name())).orElse(null);
        return new ShipmentView(s.getId(), s.getTrackingNumber(), s.getMerchantOrderRef(), s.getMerchantId(),
                s.getStatus().name(), s.getSource(), carrier, s.getCarrierAwb(),
                s.getShippingCost() == null ? null : new ShipmentView.Amount(s.getShippingCost(), s.getCurrency()),
                s.getPaymentMode().name(),
                s.getCodAmount() == null ? null : new ShipmentView.Amount(s.getCodAmount(), s.getCurrency()),
                new ShipmentView.Amount(s.getDeclaredValue(), s.getCurrency()), s.getWeightG(),
                new ShipmentView.Destination(s.getDestinationPincode(), s.getZoneCode(), s.getCity(), s.getCountryCode()),
                s.getDispatchDate(), s.getPromisedDate(), tree(s.getEddExplanation()),
                new ShipmentView.Risk(s.getRiskScore(), s.getRiskBand(), tree(s.getRiskAssessment())),
                s.getAttemptCount(), s.getLastNdrReason(), s.getDeliveredAt(), s.getCreatedAt(), s.getUpdatedAt(),
                s.getVersion());
    }

    public JsonNode tree(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
