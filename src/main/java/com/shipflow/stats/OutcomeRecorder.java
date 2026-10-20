package com.shipflow.stats;

import com.shipflow.network.NetworkDirectory;
import com.shipflow.shipment.application.ShipmentTransitionListener;
import com.shipflow.shipment.application.TransitionEvent;
import com.shipflow.shipment.domain.Shipment;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Captures the outcomes that feed the self-learning scorecard (Feature A), lane lead-time histograms (Feature D)
 * and delivery-risk stats (Feature C) as one append-only row in the transition's own transaction.
 * The counters themselves are updated by {@link StatsAggregator} (write-behind), so a scan never waits on a
 * shared hot counter row.
 * <ul>
 *   <li>DELIVERED: scorecard delivered/on_time/first_attempt, lane lead-time sample, pincode+customer attempt</li>
 *   <li>DELIVERY_FAILED: pincode+customer failed attempt</li>
 *   <li>RTO_INITIATED: scorecard rto, customer rto</li>
 * </ul>
 */
@Component
public class OutcomeRecorder implements ShipmentTransitionListener {
    private final OutcomeLogRepository log;
    private final NetworkDirectory network;

    public OutcomeRecorder(OutcomeLogRepository log, NetworkDirectory network) {
        this.log = log;
        this.network = network;
    }

    @Override
    public void onTransition(TransitionEvent e) {
        DeliveryOutcome.Kind kind = switch (e.to()) {
            case DELIVERED -> DeliveryOutcome.Kind.DELIVERED;
            case DELIVERY_FAILED -> DeliveryOutcome.Kind.FAILED;
            case RTO_INITIATED -> DeliveryOutcome.Kind.RTO;
            default -> null;
        };
        if (kind == null) {
            return;
        }
        Shipment s = e.shipment();
        LocalDate day = e.occurredAt().atZone(network.country(s.getCountryCode()).zone()).toLocalDate();
        Integer lead = null;
        boolean onTime = false;
        boolean firstAttempt = false;
        if (kind == DeliveryOutcome.Kind.DELIVERED) {
            onTime = s.getPromisedDate() == null || !day.isAfter(s.getPromisedDate());
            firstAttempt = s.getAttemptCount() <= 1;
            if (s.getDispatchDate() != null) {
                lead = Math.max(0, network.calendar(s.getCountryCode()).businessDaysBetween(s.getDispatchDate(), day));
            }
        }
        log.append(s.getId(), new DeliveryOutcome(kind, s.getCarrierId(), s.getOriginHubId(), s.getZoneCode(), day,
                lead, onTime, firstAttempt, s.getDestinationPincode(), s.getConsigneePhoneHash()));
    }
}
