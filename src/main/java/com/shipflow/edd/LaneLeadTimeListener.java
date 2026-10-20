package com.shipflow.edd;

import com.shipflow.network.NetworkDirectory;
import com.shipflow.shipment.application.ShipmentTransitionListener;
import com.shipflow.shipment.application.TransitionEvent;
import com.shipflow.shipment.domain.Shipment;
import com.shipflow.shipment.domain.ShipmentStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** On DELIVERED, adds one sample (business days dispatch -> delivery) to the lane's lead-time histogram. */
@Component
public class LaneLeadTimeListener implements ShipmentTransitionListener {
    private final LaneLeadTimeRepository lanes;
    private final NetworkDirectory network;

    public LaneLeadTimeListener(LaneLeadTimeRepository lanes, NetworkDirectory network) {
        this.lanes = lanes;
        this.network = network;
    }

    @Override
    public void onTransition(TransitionEvent e) {
        Shipment s = e.shipment();
        if (e.to() != ShipmentStatus.DELIVERED || s.getDispatchDate() == null || s.getCarrierId() == null) {
            return;
        }
        LocalDate delivered = e.occurredAt().atZone(network.country(s.getCountryCode()).zone()).toLocalDate();
        int lead = network.calendar(s.getCountryCode()).businessDaysBetween(s.getDispatchDate(), delivered);
        lanes.record(s.getCarrierId(), s.getOriginHubId(), s.getZoneCode(), Math.max(lead, 0));
    }
}
