package com.shipflow.tracking;

import com.shipflow.common.error.ShipFlowException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * coarse event timeline. Two indexed queries (shipment by unique tracking number + its history).
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

    private final JdbcTemplate jdbc;

    public TrackingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{trackingNumber}")
    public ResponseEntity<TrackingView> track(@PathVariable String trackingNumber) {
        if (!TRACKING.matcher(trackingNumber).matches()) {
            throw ShipFlowException.notFound("tracking number", trackingNumber);
        }
        record Head(java.util.UUID id, String status, String carrier, String city, LocalDate promised,
                    Instant delivered, int attempts) {
        }
        Head h = jdbc.query("""
                SELECT s.id, s.status, c.name, s.city, s.promised_date, s.delivered_at, s.attempt_count
                  FROM shipment s LEFT JOIN carrier c ON c.id = s.carrier_id WHERE s.tracking_number = ?
                """, (rs, i) -> new Head(rs.getObject(1, java.util.UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getInt(7)), trackingNumber)
                .stream().findFirst().orElseThrow(() -> ShipFlowException.notFound("tracking number", trackingNumber));
        List<TrackingEvent> events = jdbc.query("""
                SELECT to_status, occurred_at, location FROM shipment_status_history
                 WHERE shipment_id = ? AND from_status IS DISTINCT FROM to_status ORDER BY occurred_at, id
                """, (rs, i) -> new TrackingEvent(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3)), h.id());
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic())
                .body(new TrackingView(trackingNumber, h.status(), h.carrier(), h.city(), h.promised(), h.delivered(),
                        h.attempts(), events));
    }
}
