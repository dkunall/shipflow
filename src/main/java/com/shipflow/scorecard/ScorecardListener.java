package com.shipflow.scorecard;

import com.shipflow.network.NetworkDirectory;
import com.shipflow.shipment.application.ShipmentTransitionListener;
import com.shipflow.shipment.application.TransitionEvent;
import com.shipflow.shipment.domain.Shipment;
import com.shipflow.shipment.domain.ShipmentStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Feeds the self-learning scorecard incrementally from lifecycle outcomes:
 * DELIVERED -> delivered+1, on_time+1 if delivered on/before promised date, first_attempt+1 if attempt #1;
 * RTO_INITIATED -> rto+1. Bucketed by the outcome's local date in the destination country.
 */
@Component
public class ScorecardListener implements ShipmentTransitionListener {
    private final ScorecardRepository repository;
    private final NetworkDirectory network;

    public ScorecardListener(ScorecardRepository repository, NetworkDirectory network) {
        this.repository = repository;
        this.network = network;
    }

    @Override
    public void onTransition(TransitionEvent e) {
        Shipment s = e.shipment();
        if (s.getCarrierId() == null) {
            return;
        }
        LocalDate day = e.occurredAt().atZone(network.country(s.getCountryCode()).zone()).toLocalDate();
        if (e.to() == ShipmentStatus.DELIVERED) {
            boolean onTime = s.getPromisedDate() == null || !day.isAfter(s.getPromisedDate());
            repository.recordDelivered(s.getCarrierId(), s.getZoneCode(), day, onTime, s.getAttemptCount() <= 1);
        } else if (e.to() == ShipmentStatus.RTO_INITIATED) {
            repository.recordRto(s.getCarrierId(), s.getZoneCode(), day);
        }
    }
}
