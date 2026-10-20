package com.shipflow.risk;

import com.shipflow.shipment.application.ShipmentTransitionListener;
import com.shipflow.shipment.application.TransitionEvent;
import com.shipflow.shipment.domain.ShipmentStatus;
import org.springframework.stereotype.Component;

/** Keeps the risk inputs (area failure rate, customer history) up to date incrementally from outcomes. */
@Component
public class RiskStatsListener implements ShipmentTransitionListener {
    private final RiskStatsRepository stats;

    public RiskStatsListener(RiskStatsRepository stats) {
        this.stats = stats;
    }

    @Override
    public void onTransition(TransitionEvent e) {
        var s = e.shipment();
        switch (e.to()) {
            case DELIVERED -> stats.recordAttempt(s.getDestinationPincode(), s.getConsigneePhoneHash(), false);
            case DELIVERY_FAILED -> stats.recordAttempt(s.getDestinationPincode(), s.getConsigneePhoneHash(), true);
            case RTO_INITIATED -> stats.recordRto(s.getConsigneePhoneHash());
            default -> {
                // not a risk signal
            }
        }
    }
}
