package com.shipflow.webhook;

import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.carrier.domain.CarrierInfo;
import com.shipflow.shipment.application.ShipmentTransitionListener;
import com.shipflow.shipment.application.TransitionEvent;
import com.shipflow.shipment.domain.Shipment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every status transition becomes a merchant webhook event (shipment.status_changed). */
@Component
public class ShipmentWebhookListener implements ShipmentTransitionListener {
    private final OutboxWriter outbox;
    private final CarrierDirectory carriers;

    public ShipmentWebhookListener(OutboxWriter outbox, CarrierDirectory carriers) {
        this.outbox = outbox;
        this.carriers = carriers;
    }

    @Override
    public void onTransition(TransitionEvent e) {
        Shipment s = e.shipment();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shipmentId", s.getId().toString());
        data.put("trackingNumber", s.getTrackingNumber());
        data.put("merchantOrderRef", s.getMerchantOrderRef());
        data.put("previousStatus", e.from().name());
        data.put("status", e.to().name());
        data.put("occurredAt", e.occurredAt().toString());
        data.put("inferred", e.cause().inferred());
        if (s.getCarrierId() != null) {
            data.put("carrier", carriers.byId(s.getCarrierId()).map(CarrierInfo::code).orElse(null));
        }
        if (s.getPromisedDate() != null) {
            data.put("promisedDate", s.getPromisedDate().toString());
        }
        if (e.cause().ndrReason() != null) {
            data.put("ndrReason", e.cause().ndrReason());
        }
        data.put("attempt", s.getAttemptCount());
        outbox.write(s.getMerchantId(), s.getId(), "shipment.status_changed", data);
    }
}
