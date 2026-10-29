package com.shipflow.tracking;

import com.shipflow.common.error.ShipFlowException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Public tracking page API (no auth). Exposes only non-PII fields: status, carrier, promised date, city and a
 * coarse event timeline.
 */
@RestController
@RequestMapping("/api/v1/tracking")
public class TrackingController {
    private static final Pattern TRACKING = Pattern.compile("^SF[0-9A-Z]{12}$");

    public record TrackingEvent(String status, Instant occurredAt, String location) {
    }

    public record TrackingView(String trackingNumber, String status, String carrier, String destinationCity,
                               LocalDate promisedDate, Instant deliveredAt, int deliveryAttempts,
                               List<TrackingEvent> events) {
    }

    private final TrackingQueryRepository queries;

    public TrackingController(TrackingQueryRepository queries) {
        this.queries = queries;
    }

    @GetMapping("/{trackingNumber}")
    public ResponseEntity<TrackingView> track(@PathVariable String trackingNumber) {
        if (!TRACKING.matcher(trackingNumber).matches()) {
            throw ShipFlowException.notFound("tracking number", trackingNumber);
        }
        TrackingQueryRepository.Head h = queries.head(trackingNumber)
                .orElseThrow(() -> ShipFlowException.notFound("tracking number", trackingNumber));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic())
                .body(new TrackingView(trackingNumber, h.status(), h.carrier(), h.city(), h.promised(), h.delivered(),
                        h.attempts(), queries.events(h.id())));
    }
}
