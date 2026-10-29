package com.shipflow.tracking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read model for public tracking: two indexed queries (unique tracking number, then its history). No PII. */
@Repository
public class TrackingQueryRepository {

    public record Head(UUID id, String status, String carrier, String city, LocalDate promised, Instant delivered,
                       int attempts) {
    }

    private final JdbcTemplate jdbc;

    public TrackingQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Head> head(String trackingNumber) {
        return jdbc.query("""
                SELECT s.id, s.status, c.name, s.city, s.promised_date, s.delivered_at, s.attempt_count
                  FROM shipment s LEFT JOIN carrier c ON c.id = s.carrier_id WHERE s.tracking_number = ?
                """, (rs, i) -> new Head(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getInt(7)), trackingNumber)
                .stream().findFirst();
    }

    public List<TrackingController.TrackingEvent> events(UUID shipmentId) {
        return jdbc.query("""
                SELECT to_status, occurred_at, location FROM shipment_status_history
                 WHERE shipment_id = ? AND from_status IS DISTINCT FROM to_status ORDER BY occurred_at, id
                """, (rs, i) -> new TrackingController.TrackingEvent(rs.getString(1), rs.getTimestamp(2).toInstant(),
                rs.getString(3)), shipmentId);
    }
}
